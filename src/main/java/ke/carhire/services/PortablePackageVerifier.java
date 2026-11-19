package ke.carhire.services;

import static ke.carhire.services.PortableFormat.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import ke.carhire.exceptions.ApiError;
import ke.carhire.models.*;
import ke.carhire.utils.Json;
import org.apache.commons.csv.*;
import org.springframework.stereotype.Service;

@Service
public class PortablePackageVerifier {
  private final Json json;
  private final ReportService reports;

  public PortablePackageVerifier(Json json, ReportService reports) {
    this.json = json;
    this.reports = reports;
  }

  public VerifiedExport verify(byte[] packageBytes) throws IOException {
    if (packageBytes.length > 20 * 1024 * 1024)
      throw ApiError.invalid("Package exceeds 20 MiB upload limit");
    Map<String, byte[]> files = new TreeMap<>();
    int total = 0;
    try (var zip =
        new ZipInputStream(new ByteArrayInputStream(packageBytes), StandardCharsets.UTF_8)) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        String name = entry.getName();
        if (entry.isDirectory()
            || name.contains("..")
            || name.contains("\\")
            || name.startsWith("/")
            || !name.matches("[A-Za-z0-9_./-]+")
            || files.size() >= 10000
            || files.containsKey(name)) throw ApiError.invalid("Unsafe or duplicate ZIP path");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = zip.read(buffer)) != -1) {
          total += n;
          if (total > 100 * 1024 * 1024 || total > (long) Math.max(packageBytes.length, 1024) * 100)
            throw ApiError.invalid("ZIP expansion exceeds safety limits");
          out.write(buffer, 0, n);
        }
        files.put(name, out.toByteArray());
      }
    }
    if (!files.containsKey("manifest.json")
        || !files.containsKey("checksums.sha256")
        || !files.containsKey("data/records.jsonl"))
      throw ApiError.invalid("Required manifest, checksums or typed envelope missing");
    JsonNode manifest = json.read(new String(files.get("manifest.json"), StandardCharsets.UTF_8));
    if (!manifest.path("format").asText().equals("car-hire-portable-export")
        || !manifest.path("schemaVersion").asText().equals("1.0.0")
        || !manifest.path("currency").asText().equals("KES")
        || !manifest.path("timeZone").asText().equals("Africa/Nairobi"))
      throw ApiError.invalid("Unsupported export contract");
    UUID.fromString(manifest.path("exportBatchId").asText());
    Set<String> checked = new HashSet<>();
    for (String line :
        new String(files.get("checksums.sha256"), StandardCharsets.UTF_8).split("\n")) {
      if (line.isBlank()) continue;
      String[] parts = line.split("  ", 2);
      if (parts.length != 2
          || !checked.add(parts[1])
          || !files.containsKey(parts[1])
          || !Json.hash(files.get(parts[1])).equals(parts[0]))
        throw ApiError.invalid("Checksum verification failed");
    }
    if (checked.size() != files.size() - 1 || checked.contains("checksums.sha256"))
      throw ApiError.invalid("Unverified ZIP content");
    Set<String> declared = new HashSet<>();
    for (JsonNode f : manifest.path("files")) {
      String path = f.path("path").asText();
      if (!declared.add(path)
          || !files.containsKey(path)
          || files.get(path).length != f.path("size").asLong()
          || !Json.hash(files.get(path)).equals(f.path("sha256").asText()))
        throw ApiError.invalid("Manifest file verification failed");
    }
    if (declared.size() != files.size() - 2)
      throw ApiError.invalid("Manifest does not describe every payload file");
    List<Row> rows = new ArrayList<>();
    Set<UUID> ids = new HashSet<>();
    Map<String, Integer> counts = new TreeMap<>();
    DomainPolicy.KINDS.forEach(k -> counts.put(k, 0));
    for (String line :
        new String(files.get("data/records.jsonl"), StandardCharsets.UTF_8).split("\n")) {
      if (line.isBlank()) continue;
      Row row = json.readTree(json.read(line), Row.class);
      if (row.id() == null
          || row.data() == null
          || !row.data().isObject()
          || !DomainPolicy.KINDS.contains(row.kind())
          || !ids.add(row.id())
          || rows.size() >= 50000) throw ApiError.invalid("Invalid/duplicate record in import");
      rows.add(row);
      counts.merge(row.kind(), 1, Integer::sum);
    }
    if (!json.tree(counts).equals(manifest.path("entityCounts")))
      throw ApiError.invalid("Entity counts do not reconcile");
    for (String kind : DomainPolicy.KINDS) {
      var selected = rows.stream().filter(r -> r.kind().equals(kind)).toList();
      String name = NAMES.getOrDefault(kind, kind);
      if (kind.equals("conditions")) {
        byte[] content = files.get("data/" + name + ".jsonl");
        if (content == null) throw ApiError.invalid("Missing condition checks file");
        var lines =
            new String(content, StandardCharsets.UTF_8)
                .lines()
                .filter(s -> !s.isBlank())
                .map(json::read)
                .toList();
        if (lines.size() != selected.size())
          throw ApiError.invalid("Condition check row count mismatch");
        for (int i = 0; i < lines.size(); i++)
          if (!json.hash(lines.get(i)).equals(json.hash(selected.get(i))))
            throw ApiError.invalid("Condition check envelope mismatch");
      } else {
        byte[] content = files.get("data/" + name + ".csv");
        if (content == null) throw ApiError.invalid("Required CSV missing: " + kind);
        try (var parser =
            CSVParser.parse(
                new String(content, StandardCharsets.UTF_8),
                CSVFormat.RFC4180
                    .builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setNullString("\\N")
                    .get())) {
          Map<String, Row> expected = new HashMap<>();
          selected.forEach(r -> expected.put(r.id().toString(), r));
          Set<String> seen = new HashSet<>();
          for (var csv : parser) {
            String id = csv.get("id");
            var row = expected.get(id);
            if (row == null
                || !seen.add(id)
                || !Long.toString(row.revision()).equals(csv.get("revision")))
              throw ApiError.invalid(
                  "CSV UUID/revision mismatch in " + kind + " row " + csv.getRecordNumber());
            for (var fields = row.data().fields(); fields.hasNext(); ) {
              var f = fields.next();
              String value =
                  f.getValue().isNull()
                      ? null
                      : f.getValue().isValueNode()
                          ? f.getValue().asText()
                          : json.write(f.getValue());
              if (!Objects.equals(value, csv.get(snake(f.getKey()))))
                throw ApiError.invalid(
                    "CSV value mismatch in " + kind + " row " + csv.getRecordNumber());
            }
          }
          if (seen.size() != selected.size())
            throw ApiError.invalid("CSV row count mismatch in " + kind);
        }
      }
    }
    var totals =
        reports.calculateRows(rows, LocalDate.of(1900, 1, 1), LocalDate.of(9999, 12, 31), null);
    for (String key :
        List.of(
            "recognizedMinor",
            "collectionsMinor",
            "depositCashMinor",
            "accruedExpensesMinor",
            "cashExpensesMinor",
            "expectedProfitMinor",
            "cashProfitMinor",
            "outstandingMinor")) {
      var supplied = manifest.path("financialTotals").path(key);
      if (!supplied.isIntegralNumber()
          || supplied.longValue() != ((Number) totals.get(key)).longValue())
        throw ApiError.invalid("Financial reconciliation mismatch: " + key);
    }
    return new VerifiedExport(manifest, rows, files, Json.hash(packageBytes));
  }
}
