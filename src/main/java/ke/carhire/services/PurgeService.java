package ke.carhire.services;

import static ke.carhire.services.DomainPolicy.*;

import java.time.*;
import java.util.*;
import ke.carhire.exceptions.ApiError;
import ke.carhire.models.*;
import ke.carhire.repositories.*;
import ke.carhire.utils.Json;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PurgeService {
  private final RecordRepository store;
  private final Json json;
  private final AttachmentRepository metadata;
  private final DataTransferRepository transfers;
  private final OperationRepository operations;
  private final AuditService audit;

  public PurgeService(
      RecordRepository store,
      Json json,
      AttachmentRepository metadata,
      DataTransferRepository transfers,
      OperationRepository operations,
      AuditService audit) {
    this.store = store;
    this.json = json;
    this.metadata = metadata;
    this.transfers = transfers;
    this.operations = operations;
    this.audit = audit;
  }

  @Transactional
  public Map<String, Object> preview(PurgeRequest request) {
    if (request.exportBatchId() == null || request.through() == null)
      throw ApiError.invalid("Export batch and through date are required");
    store.lock();
    var batches = transfers.exportEvidence(request.exportBatchId());
    if (batches.isEmpty()) throw ApiError.invalid("Export batch not found");
    var batch = batches.getFirst();
    if (!List.of("Ingested", "Acknowledged Safe").contains(batch.get("state")))
      throw ApiError.invalid("Purge requires Ingested or deliberately Acknowledged Safe export");
    Map<UUID, Long> exported = new HashMap<>();
    for (var n : json.read((String) batch.get("snapshot")))
      exported.put(UUID.fromString(n.path("id").asText()), n.path("revision").asLong());
    var all = store.snapshot();
    Set<UUID> eligible = new LinkedHashSet<>();
    Map<String, Integer> excluded = new TreeMap<>();
    for (var row : all) {
      if (row.deletedAt() != null) continue;
      String reason = null;
      var d = row.data();
      if (!Objects.equals(exported.get(row.id()), row.revision()))
        reason = "Not exported or changed since export";
      else if (row.kind().equals("hires")) {
        String status = text(d, "status");
        if (!List.of("Completed", "Cancelled").contains(status)) reason = "Open hire";
        else {
          LocalDate end =
              (status.equals("Completed") ? instant(d, "actualEnd") : instant(d, "plannedEnd"))
                  .atZone(ReportService.ZONE)
                  .toLocalDate();
          if (end.isAfter(request.through())) reason = "Outside scope";
        }
      } else if (List.of("expenses", "maintenance", "readings").contains(row.kind())) {
        if (text(d, "date").isEmpty() || date(d, "date").isAfter(request.through()))
          reason = "Outside scope";
        else if (row.kind().equals("expenses")
            && !List.of("Paid", "Cancelled").contains(text(d, "status")))
          reason = "Unsettled expense";
        else if (row.kind().equals("maintenance")
            && (!text(d, "nextDueDate").isEmpty() || d.has("nextDueKm")))
          reason = "Supports future maintenance";
        else if (row.kind().equals("readings")) reason = "Retained mileage evidence";
      } else reason = "Master, configuration, reminder or dependent record";
      if (reason == null) eligible.add(row.id());
      else excluded.merge(reason, 1, Integer::sum);
    }
    for (var row : all)
      if (List.of("payments", "damages", "conditions").contains(row.kind())
          && row.deletedAt() == null
          && eligible.contains(uuid(row.data(), "hireId"))
          && !(row.kind().equals("payments") && text(row.data(), "status").equals("Pending"))
          && !(row.kind().equals("damages") && text(row.data(), "status").equals("Open"))
          && Objects.equals(exported.get(row.id()), row.revision())) eligible.add(row.id());
    // A retained dependent prevents erasing its parent, including unsettled deposits.
    boolean changed;
    do {
      changed = false;
      for (var row : all)
        if (row.deletedAt() == null && !eligible.contains(row.id()))
          for (String field : List.of("hireId", "expenseId", "originalPaymentId")) {
            String value = text(row.data(), field);
            if (!value.isEmpty() && eligible.remove(UUID.fromString(value))) changed = true;
          }
    } while (changed);
    for (var row : all)
      if (row.kind().equals("payments")
          && text(row.data(), "kind").equals("Deposit")
          && eligible.contains(row.id())) {
        UUID hire = uuid(row.data(), "hireId");
        long balance =
            all.stream()
                .filter(
                    p ->
                        p.kind().equals("payments")
                            && p.deletedAt() == null
                            && text(p.data(), "hireId").equals(hire.toString())
                            && text(p.data(), "status").equals("Received"))
                .mapToLong(
                    p ->
                        switch (text(p.data(), "kind")) {
                          case "Deposit" -> p.data().path("amountMinor").asLong();
                          case "DepositRefund", "DepositApplication" ->
                              -p.data().path("amountMinor").asLong();
                          default -> 0;
                        })
                .sum();
        if (balance != 0) {
          eligible.remove(hire);
          all.stream()
              .filter(p -> text(p.data(), "hireId").equals(hire.toString()))
              .forEach(p -> eligible.remove(p.id()));
        }
      }
    Map<String, String> attachmentHashes = new HashMap<>();
    for (var a : json.read((String) batch.get("manifest")).path("attachments"))
      attachmentHashes.put(a.path("id").asText(), a.path("sha256").asText());
    var attachmentRows = metadata.purgeEvidence();
    for (var row : attachmentRows) {
      UUID parent = (UUID) row.get("parent_id");
      if (!Objects.equals(attachmentHashes.get(row.get("id").toString()), row.get("sha256"))
          && eligible.remove(parent))
        excluded.merge("Attachment absent from verified export", 1, Integer::sum);
    }
    do {
      changed = false;
      for (var row : all)
        if (row.deletedAt() == null) {
          if (!eligible.contains(row.id()))
            for (String field : List.of("hireId", "expenseId", "originalPaymentId")) {
              String value = text(row.data(), field);
              if (!value.isEmpty() && eligible.remove(UUID.fromString(value))) changed = true;
            }
          if (List.of("payments", "conditions", "damages").contains(row.kind())
              && !eligible.contains(uuid(row.data(), "hireId"))
              && eligible.remove(row.id())) changed = true;
        }
    } while (changed);
    List<String> ids = eligible.stream().map(UUID::toString).sorted().toList();
    String hash =
        json.hash(
            Map.of(
                "scope",
                request,
                "ids",
                ids,
                "revisions",
                all.stream()
                    .filter(r -> eligible.contains(r.id()))
                    .map(r -> List.of(r.id(), r.revision()))
                    .toList()));
    var chosenAttachments =
        attachmentRows.stream().filter(a -> eligible.contains(a.get("parent_id"))).toList();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("scope", request);
    result.put("previewHash", hash);
    result.put("count", ids.size());
    result.put("ids", ids);
    result.put("attachmentIds", chosenAttachments.stream().map(a -> a.get("id")).toList());
    result.put(
        "attachmentBytes",
        chosenAttachments.stream().mapToLong(a -> ((Number) a.get("size")).longValue()).sum());
    Map<String, Long> byKind = new TreeMap<>();
    all.stream()
        .filter(r -> eligible.contains(r.id()))
        .forEach(r -> byKind.merge(r.kind(), 1L, Long::sum));
    result.put("includedByKind", byKind);
    result.put(
        "excludedCount",
        all.stream().filter(r -> r.deletedAt() == null && !eligible.contains(r.id())).count());
    result.put(
        "excludedReasons",
        List.of(
            "Master/configuration/evidence/current reminder",
            "Outside date scope",
            "Changed or absent from verified export",
            "Open hire, pending payment, unsettled expense/damage/deposit",
            "Referenced by retained record",
            "Attachment missing matching exported hash",
            "Mileage evidence or future maintenance"));
    result.put(
        "chargeMinor",
        all.stream()
            .filter(r -> eligible.contains(r.id()) && r.kind().equals("hires"))
            .mapToLong(r -> r.data().path("chargeMinor").asLong())
            .sum());
    result.put(
        "expenseMinor",
        all.stream()
            .filter(r -> eligible.contains(r.id()) && r.kind().equals("expenses"))
            .mapToLong(r -> r.data().path("amountMinor").asLong())
            .sum());
    result.put(
        "warning",
        "No undo. Payloads are erased; minimal UUID tombstones and immutable evidence remain. Only matching exported attachments are queued for post-commit deletion.");
    return result;
  }

  @Transactional
  public Map<String, Object> commit(PurgeConfirmation c) {
    var p = preview(c.scope());
    if (!Objects.equals(p.get("previewHash"), c.previewHash()))
      throw new ApiError(org.springframework.http.HttpStatus.CONFLICT, "Purge preview is stale");
    int count = (Integer) p.get("count");
    if (count == 0 || !Objects.equals(c.confirmation(), "PURGE " + count))
      throw ApiError.invalid("Type PURGE " + count + " to confirm");
    for (Object id : (List<?>) p.get("attachmentIds")) {
      UUID attachment = UUID.fromString(id.toString());
      metadata.queueDeletion(attachment);
    }
    for (Object id : (List<?>) p.get("ids")) {
      UUID entity = UUID.fromString(id.toString());
      store.purge(entity);
      var tombstone = store.find(entity).orElseThrow();
      operations.scrub(entity, json.write(tombstone));
    }
    UUID batch = UUID.randomUUID();
    transfers.savePurge(batch, c.scope().exportBatchId(), c.previewHash(), json.write(p));
    audit.record("PURGE", batch, null, p, null);
    return Map.of("purgeBatchId", batch, "purged", count);
  }
}
