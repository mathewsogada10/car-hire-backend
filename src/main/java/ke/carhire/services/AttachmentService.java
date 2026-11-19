package ke.carhire.services;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import ke.carhire.exceptions.ApiError;
import ke.carhire.models.*;
import ke.carhire.repositories.*;
import ke.carhire.utils.Json;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttachmentService {
  private final Path root;
  private final RecordRepository store;
  private final AttachmentRepository metadata;
  private final AuditService audit;

  public AttachmentService(
      @Value("${carhire.storage}") String root,
      RecordRepository store,
      AttachmentRepository metadata,
      AuditService audit)
      throws IOException {
    this.root = Path.of(root).toAbsolutePath().normalize();
    this.store = store;
    this.metadata = metadata;
    this.audit = audit;
    Files.createDirectories(this.root);
  }

  @Transactional
  public Map<String, Object> upload(UUID parent, UUID requestedId, AttachmentUpload file)
      throws IOException {
    store.lock();
    var row = store.find(parent).orElseThrow(() -> ApiError.invalid("Attachment parent missing"));
    if (row.deletedAt() != null) throw ApiError.invalid("Parent is deleted");
    byte[] bytes = file.bytes();
    if (bytes.length == 0 || bytes.length > 20 * 1024 * 1024)
      throw ApiError.invalid("Attachment must be 1 byte to 20 MiB");
    String mime = detect(bytes);
    if (!mime.equals(file.contentType()))
      throw ApiError.invalid("File content does not match allowed PDF/JPEG/PNG type");
    String hash = Json.hash(bytes);
    UUID id = requestedId == null ? UUID.randomUUID() : requestedId;
    var existing = metadata.identity(id);
    if (!existing.isEmpty()) {
      if (!parent.equals(existing.getFirst().get("parent_id"))
          || !hash.equals(existing.getFirst().get("sha256")))
        throw new ApiError(HttpStatus.CONFLICT, "Attachment UUID content conflict");
      return Map.of("id", id, "parentId", parent, "sha256", hash);
    }
    Long count = metadata.count(parent);
    if (count != null && count >= 20) throw ApiError.invalid("Maximum 20 attachments per record");
    String filename =
        Optional.ofNullable(file.filename())
            .orElse("attachment")
            .replaceAll("[^A-Za-z0-9._-]", "_");
    if (filename.length() > 120) filename = filename.substring(filename.length() - 120);
    write(id, bytes);
    metadata.save(id, parent, filename, mime, bytes.length, hash);
    audit.record("ATTACHMENT_CREATE", id, null, hash, null);
    return Map.of(
        "id",
        id,
        "parentId",
        parent,
        "filename",
        filename,
        "mime",
        mime,
        "size",
        bytes.length,
        "sha256",
        hash);
  }

  static String detect(byte[] b) {
    if (b.length >= 5
        && new String(b, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))
      return "application/pdf";
    if (b.length >= 8
        && b[0] == (byte) 137
        && b[1] == 80
        && b[2] == 78
        && b[3] == 71
        && b[4] == 13
        && b[5] == 10
        && b[6] == 26
        && b[7] == 10) return "image/png";
    if (b.length >= 3 && b[0] == (byte) 255 && b[1] == (byte) 216 && b[2] == (byte) 255)
      return "image/jpeg";
    throw ApiError.invalid("Only PDF, PNG and JPEG files are supported");
  }

  public byte[] bytes(UUID id, String expectedHash) throws IOException {
    byte[] bytes = Files.readAllBytes(root.resolve(id.toString()));
    if (!Json.hash(bytes).equals(expectedHash))
      throw ApiError.invalid("Attachment integrity check failed");
    return bytes;
  }

  @Transactional
  public Map<String, Object> cleanup() {
    store.lock();
    int cleaned = 0;
    for (var item : metadata.pendingCleanup()) {
      UUID id = (UUID) item.get("id");
      try {
        Files.deleteIfExists(root.resolve(id.toString()));
        metadata.completeCleanup(id);
        cleaned++;
      } catch (IOException e) {
        metadata.failCleanup(id);
      }
    }
    return Map.of("cleaned", cleaned, "remaining", metadata.pendingCleanupCount());
  }

  private void write(UUID id, byte[] bytes) throws IOException {
    Path path = root.resolve(id.toString());
    Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
    org.springframework.transaction.support.TransactionSynchronizationManager
        .registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
              @Override
              public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED)
                  try {
                    Files.deleteIfExists(path);
                  } catch (IOException e) {
                    org.slf4j.LoggerFactory.getLogger(AttachmentService.class)
                        .error("Failed to clean rolled-back attachment {}", id);
                  }
              }
            });
  }

  @Transactional
  public void importFile(
      UUID id, UUID parent, String filename, String mime, byte[] content, String hash)
      throws IOException {
    store.lock();
    var old = metadata.identity(id);
    if (!old.isEmpty()) {
      if (!parent.equals(old.getFirst().get("parent_id"))
          || !hash.equals(old.getFirst().get("sha256")))
        throw new ApiError(HttpStatus.CONFLICT, "Attachment UUID content conflict");
      return;
    }
    write(id, content);
    metadata.save(id, parent, filename, mime, content.length, hash);
  }

  public List<Map<String, Object>> list(UUID parent) {
    return metadata.list(parent);
  }

  public AttachmentDownload download(UUID id) throws IOException {
    var rows = metadata.find(id);
    if (rows.isEmpty()) throw new ApiError(HttpStatus.NOT_FOUND, "Attachment not found");
    var row = rows.getFirst();
    return new AttachmentDownload(
        (String) row.get("filename"),
        (String) row.get("mime"),
        bytes(id, (String) row.get("sha256")));
  }

  public Map<String, Object> changes(long after) {
    if (after < 0) throw ApiError.invalid("Invalid attachment cursor");
    var items = metadata.changes(after);
    long next = items.isEmpty() ? after : ((Number) items.getLast().get("revision")).longValue();
    return Map.of("items", items, "cursor", Long.toString(next), "hasMore", items.size() == 100);
  }
}
