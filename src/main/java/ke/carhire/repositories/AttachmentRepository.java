package ke.carhire.repositories;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AttachmentRepository {
  private final JdbcTemplate db;

  public AttachmentRepository(JdbcTemplate db) {
    this.db = db;
  }

  public List<Map<String, Object>> identity(UUID id) {
    return db.queryForList("SELECT parent_id,sha256 FROM attachments WHERE id=?", id);
  }

  public List<Map<String, Object>> find(UUID id) {
    return db.queryForList("SELECT * FROM attachments WHERE id=?", id);
  }

  public List<Map<String, Object>> list(UUID parent) {
    return db.queryForList(
        "SELECT id,parent_id,filename,mime,size,sha256 FROM attachments WHERE parent_id=?", parent);
  }

  public List<Map<String, Object>> snapshot() {
    return db.queryForList(
        "SELECT id,parent_id,filename,mime,size,sha256 FROM attachments ORDER BY id");
  }

  public List<Map<String, Object>> purgeEvidence() {
    return db.queryForList("SELECT id,parent_id,sha256,size FROM attachments");
  }

  public Long count(UUID parent) {
    return db.queryForObject(
        "SELECT count(*) FROM attachments WHERE parent_id=?", Long.class, parent);
  }

  public void save(UUID id, UUID parent, String filename, String mime, long size, String hash) {
    db.update(
        "INSERT INTO attachments(id,parent_id,filename,mime,size,sha256) VALUES (?,?,?,?,?,?)",
        id,
        parent,
        filename,
        mime,
        size,
        hash);
  }

  public List<Map<String, Object>> changes(long after) {
    return db.queryForList(
        "SELECT a.id,a.parent_id AS \"parentId\",a.filename,a.mime,a.size,a.sha256,a.revision FROM attachments a JOIN records r ON a.parent_id=r.id WHERE a.revision>? AND r.deleted_at IS NULL ORDER BY a.revision LIMIT 100",
        after);
  }

  public void queueDeletion(UUID id) {
    db.update("INSERT INTO file_cleanup(id) VALUES (?) ON CONFLICT DO NOTHING", id);
    db.update("DELETE FROM attachments WHERE id=?", id);
  }

  public List<Map<String, Object>> pendingCleanup() {
    return db.queryForList("SELECT id FROM file_cleanup");
  }

  public void completeCleanup(UUID id) {
    db.update("DELETE FROM file_cleanup WHERE id=?", id);
  }

  public void failCleanup(UUID id) {
    db.update(
        "UPDATE file_cleanup SET attempts=attempts+1,last_error='File deletion failed; check storage permissions' WHERE id=?",
        id);
  }

  public Long pendingCleanupCount() {
    return db.queryForObject("SELECT count(*) FROM file_cleanup", Long.class);
  }
}
