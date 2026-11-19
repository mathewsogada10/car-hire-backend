package ke.carhire.services;

import java.util.*;
import ke.carhire.exceptions.ApiError;
import ke.carhire.repositories.DataTransferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExportHistoryService {
  private final DataTransferRepository repository;
  private final AuditService audit;

  public ExportHistoryService(DataTransferRepository repository, AuditService audit) {
    this.repository = repository;
    this.audit = audit;
  }

  public List<Map<String, Object>> list() {
    return repository.exports();
  }

  @Transactional
  public void acknowledge(UUID id, Map<String, String> body) {
    if (!"I VERIFIED AND SAFELY STORED THIS EXPORT".equals(body.get("confirmation")))
      throw ApiError.invalid("Explicit safe-storage confirmation required");
    if (repository.acknowledge(id) != 1)
      throw ApiError.invalid("Only a verified export can be acknowledged");
    audit.record(
        "EXPORT_SAFE_ACK", id, null, body.get("confirmation"), body.get("destinationNote"));
  }
}
