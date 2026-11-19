package ke.carhire.services;

import java.nio.charset.StandardCharsets;
import java.util.*;
import ke.carhire.exceptions.ApiError;
import ke.carhire.models.Push;
import ke.carhire.repositories.RecordRepository;
import org.springframework.stereotype.Service;

@Service
public class SyncService {
  private final RecordService records;
  private final RecordRepository repository;

  public SyncService(RecordService records, RecordRepository repository) {
    this.records = records;
    this.repository = repository;
  }

  public Map<String, Object> push(Push push) {
    if (push.operations() == null
        || push.operations().size() > 100
        || push.installationId() == null
        || push.installationId().length() > 100)
      throw ApiError.invalid("Push requires installationId and at most 100 operations");
    if (push.operations().stream().anyMatch(op -> op == null || op.operationId() == null))
      throw ApiError.invalid("Every operation needs an operationId");
    List<Object> outcomes = new ArrayList<>();
    for (var op : push.operations())
      try {
        outcomes.add(records.apply(op));
      } catch (ApiError e) {
        outcomes.add(records.reject(op, e.getMessage()));
      } catch (org.springframework.dao.DataIntegrityViolationException e) {
        outcomes.add(
            records.reject(op, "A unique value or referenced record conflicts with this change"));
      }
    return Map.of("results", outcomes);
  }

  public Map<String, Object> pull(String cursor, int limit) {
    if (limit < 1 || limit > 200) throw ApiError.invalid("Pull limit must be 1..200");
    long revision = 0;
    if (!cursor.isEmpty())
      try {
        revision =
            Long.parseLong(
                new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
        if (revision < 0) throw new NumberFormatException();
      } catch (IllegalArgumentException e) {
        throw ApiError.invalid("Invalid sync cursor");
      }
    var rows = repository.changes(revision, limit);
    long next = rows.isEmpty() ? revision : rows.getLast().revision();
    return Map.of(
        "items",
        rows,
        "cursor",
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(Long.toString(next).getBytes(StandardCharsets.UTF_8)),
        "hasMore",
        rows.size() == limit);
  }
}
