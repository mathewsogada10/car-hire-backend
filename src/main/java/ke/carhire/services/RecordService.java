package ke.carhire.services;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import ke.carhire.exceptions.ApiError;
import ke.carhire.models.*;
import ke.carhire.repositories.*;
import ke.carhire.utils.Json;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecordService {
  private final RecordRepository store;
  private final DomainPolicy policy;
  private final Json json;
  private final OperationRepository operations;
  private final AuditService audit;

  public RecordService(
      RecordRepository store,
      DomainPolicy policy,
      Json json,
      OperationRepository operations,
      AuditService audit) {
    this.store = store;
    this.policy = policy;
    this.json = json;
    this.operations = operations;
    this.audit = audit;
  }

  public Map<String, Object> list(
      String kind,
      int page,
      int size,
      String query,
      String status,
      UUID vehicle,
      String from,
      String to) {
    if (!DomainPolicy.KINDS.contains(kind) || page < 0 || size < 1 || size > 200)
      throw ApiError.invalid("Invalid record type or pagination");
    if (!from.isBlank()) java.time.LocalDate.parse(from);
    if (!to.isBlank()) java.time.LocalDate.parse(to);
    return store.page(kind, page, size, query, status, vehicle, from, to);
  }

  public Row get(String kind, UUID id) {
    return store.require(id, kind);
  }

  @Transactional
  public Map<String, Object> apply(Change change) {
    if (change.operationId() == null
        || change.id() == null
        || change.data() == null
        || change.kind() == null
        || change.baseRevision() < 0)
      throw ApiError.invalid("operationId, id, kind, baseRevision and data are required");
    store.lock();
    String hash = json.hash(change);
    var prior = operations.find(change.operationId());
    if (!prior.isEmpty()) {
      if (!prior.getFirst().get("request_hash").equals(hash))
        throw new ApiError(
            org.springframework.http.HttpStatus.CONFLICT,
            "Operation ID was already used with different content");
      return json.map(json.read((String) prior.getFirst().get("result")));
    }
    var old = store.find(change.id()).orElse(null);
    Map<String, Object> result;
    if (old != null
        && old.deletedAt() == null
        && old.kind().equals(change.kind())
        && !change.deleted()
        && old.data().equals(change.data())) {
      result = Map.of("outcome", "Unchanged", "operationId", change.operationId(), "record", old);
    } else if ((old == null ? 0 : old.revision()) != change.baseRevision()
        || old != null && (!old.kind().equals(change.kind()) || old.deletedAt() != null)) {
      result = new LinkedHashMap<>();
      result.put("outcome", "Conflict");
      result.put("operationId", change.operationId());
      result.put("server", old);
      result.put("local", change);
    } else {
      ObjectNode data = change.data().deepCopy();
      if (change.deleted()) {
        if (old == null) throw ApiError.invalid("Cannot delete a missing record");
        policy.deletion(old);
      } else policy.validate(change.kind(), change.id(), data, old);
      if (old != null && old.data().equals(data) && !change.deleted())
        result = Map.of("outcome", "Unchanged", "operationId", change.operationId(), "record", old);
      else {
        if (change.kind().equals("payments") && !change.deleted()) defaultHireTimes(data);
        var saved = store.save(change.id(), change.kind(), data, old, change.deleted());
        audit.record(
            change.deleted() ? "DELETE" : old == null ? "CREATE" : "UPDATE",
            saved.id(),
            old,
            saved,
            change.reason());
        result =
            Map.of("outcome", "Accepted", "operationId", change.operationId(), "record", saved);
      }
    }
    operations.save(change.operationId(), hash, json.write(result));
    return result;
  }

  private void defaultHireTimes(ObjectNode payment) {
    Row hire = store.require(UUID.fromString(payment.path("hireId").asText()), "hires");
    ObjectNode data = hire.data().deepCopy();
    for (var field : Map.of("actualStart", "plannedStart", "actualEnd", "plannedEnd").entrySet()) {
      if (data.path(field.getKey()).asText("").isBlank())
        data.put(field.getKey(), data.path(field.getValue()).asText());
    }
    if (!data.equals(hire.data())) {
      policy.validate("hires", hire.id(), data, hire);
      Row updated = store.save(hire.id(), "hires", data, hire, false);
      audit.record(
          "DEFAULT_HIRE_TIMES",
          hire.id(),
          hire,
          updated,
          "Missing actual times defaulted from plan when recording payment");
    }
  }

  @Transactional
  public Map<String, Object> reject(Change change, String message) {
    if (change == null || change.operationId() == null)
      throw ApiError.invalid("Every operation requires an operationId");
    store.lock();
    String hash = json.hash(change);
    var prior = operations.find(change.operationId());
    if (!prior.isEmpty()) {
      if (!hash.equals(prior.getFirst().get("request_hash")))
        throw new ApiError(
            org.springframework.http.HttpStatus.CONFLICT, "Operation ID content changed");
      return json.map(json.read((String) prior.getFirst().get("result")));
    }
    var result =
        Map.<String, Object>of(
            "operationId", change.operationId(), "outcome", "Rejected", "message", message);
    operations.save(change.operationId(), hash, json.write(result));
    audit.record("REJECTED_CHANGE", change.id(), null, hash, null);
    return result;
  }
}
