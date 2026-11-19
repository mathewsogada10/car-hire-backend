package ke.carhire.services;

import static ke.carhire.services.PortableFormat.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import ke.carhire.exceptions.ApiError;
import ke.carhire.models.*;
import ke.carhire.repositories.*;
import ke.carhire.utils.Json;
import org.apache.commons.csv.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PortableService {
  private final RecordRepository store;
  private final Json json;
  private final DomainPolicy policy;
  private final AttachmentService attachments;
  private final ReportService reports;
  private final AttachmentRepository metadata;
  private final DataTransferRepository transfers;
  private final AuditService audit;
  private final PortablePackageVerifier verifier;

  public PortableService(
      RecordRepository store,
      Json json,
      DomainPolicy policy,
      AttachmentService attachments,
      ReportService reports,
      AttachmentRepository metadata,
      DataTransferRepository transfers,
      AuditService audit,
      PortablePackageVerifier verifier) {
    this.store = store;
    this.json = json;
    this.policy = policy;
    this.attachments = attachments;
    this.reports = reports;
    this.metadata = metadata;
    this.transfers = transfers;
    this.audit = audit;
    this.verifier = verifier;
  }

  @Transactional
  public ExportPackage create(boolean includeAttachments) throws IOException {
    store.lock();
    UUID id = UUID.randomUUID();
    var snapshot = store.snapshot();
    Map<String, byte[]> files = new TreeMap<>();
    Map<String, Integer> counts = new TreeMap<>();
    for (String kind : new TreeSet<>(DomainPolicy.KINDS)) {
      var rows = snapshot.stream().filter(r -> r.kind().equals(kind)).toList();
      counts.put(kind, rows.size());
      String name = NAMES.getOrDefault(kind, kind);
      if (kind.equals("conditions"))
        files.put(
            "data/" + name + ".jsonl",
            utf8(
                String.join("\n", rows.stream().map(json::write).toList())
                    + (rows.isEmpty() ? "" : "\n")));
      else {
        var out = new StringWriter();
        TreeSet<String> fields = new TreeSet<>();
        rows.forEach(r -> r.data().fieldNames().forEachRemaining(fields::add));
        List<String> headers =
            new ArrayList<>(List.of("id", "revision", "created_at", "updated_at", "deleted_at"));
        fields.forEach(f -> headers.add(snake(f)));
        try (var csv =
            new CSVPrinter(
                out,
                CSVFormat.RFC4180
                    .builder()
                    .setRecordSeparator("\n")
                    .setNullString("\\N")
                    .setHeader(headers.toArray(String[]::new))
                    .get())) {
          for (var row : rows) {
            List<Object> cells =
                new ArrayList<>(
                    Arrays.asList(
                        row.id(),
                        row.revision(),
                        row.createdAt(),
                        row.updatedAt(),
                        row.deletedAt()));
            for (String f : fields) {
              JsonNode v = row.data().get(f);
              cells.add(
                  v == null || v.isNull() ? null : v.isValueNode() ? v.asText() : json.write(v));
            }
            csv.printRecord(cells);
          }
        }
        files.put("data/" + name + ".csv", utf8(out.toString()));
      }
    }
    // Lossless typed envelope supports extension fields without CSV type guessing.
    files.put(
        "data/records.jsonl",
        utf8(String.join("\n", snapshot.stream().map(json::write).toList()) + "\n"));
    List<Map<String, Object>> attachmentRows = new ArrayList<>();
    if (includeAttachments)
      for (var a : metadata.snapshot()) {
        UUID aid = (UUID) a.get("id");
        byte[] content = attachments.bytes(aid, (String) a.get("sha256"));
        String path = "attachments/" + aid + "/" + a.get("filename");
        files.put(path, content);
        Map<String, Object> metadata = new LinkedHashMap<>(a);
        metadata.put("path", path);
        attachmentRows.add(metadata);
      }
    files.put(
        "README.txt",
        utf8(
            "Private car-hire portable export. UTF-8 RFC4180 CSV, LF, \\N=null, integer KES minor units. data/records.jsonl is the required lossless typed import envelope. Verify checksums before import. External exported copies cannot be revoked.\n"));
    var metadata = new ArrayList<Map<String, Object>>();
    for (var f : files.entrySet())
      metadata.add(
          Map.of(
              "path", f.getKey(), "size", f.getValue().length, "sha256", Json.hash(f.getValue())));
    ObjectNode manifest = json.object();
    manifest.put("format", "car-hire-portable-export");
    manifest.put("schemaVersion", "1.0.0");
    manifest.put("exportBatchId", id.toString());
    manifest.put("createdAt", Instant.now().toString());
    manifest.put("sourceApp", "car-hire-backend");
    manifest.put("sourceVersion", "0.1.0");
    manifest.put("installationId", "server");
    manifest.put("currency", "KES");
    manifest.put("timeZone", "Africa/Nairobi");
    manifest.put("scope", "Full snapshot; all dates and vehicles");
    manifest.put("dateBasis", "Each entity business date");
    manifest.set("files", json.tree(metadata));
    manifest.set("entityCounts", json.tree(counts));
    manifest.set(
        "financialTotals",
        json.tree(reports.calculate(LocalDate.of(1900, 1, 1), LocalDate.of(9999, 12, 31), null)));
    manifest.put(
        "attachmentPolicy",
        includeAttachments
            ? "Explicitly included"
            : "Excluded; attachment parents are not purgeable");
    manifest.set("attachments", json.tree(attachmentRows));
    manifest.put("verificationAlgorithm", "SHA-256");
    manifest.put("minimumImporter", "0.1.0");
    manifest.put("privacy", "Private business/customer data; store securely");
    files.put("manifest.json", utf8(json.write(manifest)));
    StringBuilder sums = new StringBuilder();
    files.forEach(
        (name, bytes) -> sums.append(Json.hash(bytes)).append("  ").append(name).append('\n'));
    files.put("checksums.sha256", utf8(sums.toString()));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
      for (var f : files.entrySet()) {
        zip.putNextEntry(new ZipEntry(f.getKey()));
        zip.write(f.getValue());
        zip.closeEntry();
      }
    }
    byte[] bytes = output.toByteArray();
    verify(bytes);
    var evidence =
        snapshot.stream()
            .map(
                r ->
                    Map.of(
                        "id", r.id(), "revision", r.revision(), "contentHash", json.hash(r.data())))
            .toList();
    transfers.saveExport(id, json.write(manifest), json.write(evidence));
    audit.record("EXPORT_VERIFIED", id, null, manifest, null);
    return new ExportPackage(id, bytes);
  }

  public VerifiedExport verify(byte[] bytes) throws IOException {
    return verifier.verify(bytes);
  }

  @Transactional
  public Map<String, Object> ingest(byte[] bytes, boolean commit, String expectedHash)
      throws IOException {
    store.lock();
    VerifiedExport p = verify(bytes);
    UUID source = UUID.fromString(p.manifest().path("exportBatchId").asText());
    var previous = transfers.findImport(source);
    if (!previous.isEmpty()) {
      if (!previous.getFirst().get("package_hash").equals(p.hash()))
        throw ApiError.invalid("Export batch ID reused with different content");
      return json.map(json.read((String) previous.getFirst().get("result")));
    }
    List<Row> inserts = new ArrayList<>();
    List<UUID> conflicts = new ArrayList<>();
    int unchanged = 0;
    for (var row : p.rows()) {
      var old = store.find(row.id()).orElse(null);
      if (old == null) inserts.add(row);
      else if (old.kind().equals(row.kind())
          && old.data().equals(row.data())
          && Objects.equals(old.deletedAt(), row.deletedAt())) unchanged++;
      else conflicts.add(row.id());
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("sourceExportBatchId", source);
    result.put("packageHash", p.hash());
    result.put("inserted", inserts.size());
    result.put("updated", 0);
    result.put("unchanged", unchanged);
    result.put("conflicted", conflicts);
    result.put("entityCounts", p.manifest().path("entityCounts"));
    result.put("sourceFinancialTotals", p.manifest().path("financialTotals"));
    result.put("status", "Validated; existing changed UUIDs require explicit conflict resolution");
    if (!commit) return result;
    if (!p.hash().equals(expectedHash)) throw ApiError.invalid("Package changed after preview");
    if (!conflicts.isEmpty())
      throw new ApiError(
          org.springframework.http.HttpStatus.CONFLICT,
          "Import has unresolved conflicts",
          conflicts);
    for (var row : inserts) {
      store.save(row.id(), row.kind(), (ObjectNode) row.data(), null, false);
      if (row.deletedAt() != null) store.restoreDeletedAt(row.id(), row.deletedAt());
    }
    for (var row : inserts)
      if (row.deletedAt() == null) {
        String original = Json.hash(json.write(row.data()).getBytes(StandardCharsets.UTF_8));
        policy.validate(row.kind(), row.id(), (ObjectNode) row.data(), null);
        if (!original.equals(Json.hash(json.write(row.data()).getBytes(StandardCharsets.UTF_8))))
          throw ApiError.invalid("Import contains non-canonical field values: " + row.id());
      }
    for (JsonNode a : p.manifest().path("attachments")) {
      UUID aid = UUID.fromString(a.path("id").asText()),
          parent = UUID.fromString(a.path("parent_id").asText());
      store.find(parent).orElseThrow(() -> ApiError.invalid("Attachment parent missing"));
      String filename = a.path("filename").asText(),
          mime = a.path("mime").asText(),
          hash = a.path("sha256").asText(),
          path = a.path("path").asText();
      byte[] content = p.files().get(path);
      if (!filename.matches("[A-Za-z0-9._-]{1,120}")
          || content == null
          || content.length != a.path("size").asLong()
          || !Json.hash(content).equals(hash)
          || !AttachmentService.detect(content).equals(mime))
        throw ApiError.invalid("Attachment metadata/content mismatch");
      attachments.importFile(aid, parent, filename, mime, content, hash);
    }
    UUID importId = UUID.randomUUID();
    result.put("importBatchId", importId);
    result.put("acknowledgedAt", Instant.now());
    result.put("status", "Ingested");
    result.put(
        "destinationFinancialTotals",
        reports.calculate(LocalDate.of(1900, 1, 1), LocalDate.of(9999, 12, 31), null));
    transfers.saveImport(importId, source, p.hash(), json.write(result));
    transfers.markIngested(source);
    audit.record("IMPORT", importId, null, result, null);
    return result;
  }
}
