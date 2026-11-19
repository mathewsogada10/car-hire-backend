package ke.carhire.services;

import java.util.*;
import ke.carhire.exceptions.ApiError;
import ke.carhire.repositories.AuditRepository;
import ke.carhire.utils.Json;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
  private final AuditRepository repository;
  private final Json json;

  public AuditService(AuditRepository repository, Json json) {
    this.repository = repository;
    this.json = json;
  }

  public List<Map<String, Object>> page(int page) {
    if (page < 0) throw ApiError.invalid("Invalid page");
    return repository.page(page);
  }

  public void record(String action, UUID id, Object before, Object after, String reason) {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    repository.append(
        UUID.randomUUID(),
        authentication == null ? "system" : authentication.getName(),
        action,
        id,
        before == null ? null : json.hash(before),
        after == null ? null : json.hash(after),
        reason);
  }
}
