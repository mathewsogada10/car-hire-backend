package ke.carhire.services;

import java.util.Map;
import ke.carhire.repositories.*;
import org.springframework.stereotype.Service;

@Service
public class DataHealthService {
  private final RecordRepository records;
  private final AttachmentRepository attachments;
  private final DataTransferRepository transfers;

  public DataHealthService(
      RecordRepository records,
      AttachmentRepository attachments,
      DataTransferRepository transfers) {
    this.records = records;
    this.attachments = attachments;
    this.transfers = transfers;
  }

  public Map<String, Object> health() {
    return Map.of(
        "records",
        records.countLive(),
        "cleanupPending",
        attachments.pendingCleanupCount(),
        "lastExport",
        transfers.lastSafeExport());
  }
}
