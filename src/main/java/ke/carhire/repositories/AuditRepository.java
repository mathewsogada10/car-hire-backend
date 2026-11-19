package ke.carhire.repositories;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditRepository {
  private final JdbcTemplate db;

  public AuditRepository(JdbcTemplate db) {
    this.db = db;
  }

  public List<Map<String, Object>> page(int page) {
    return db.queryForList(
        "SELECT id,actor,action,entity_id,before_hash,after_hash,reason,created_at FROM audit_events ORDER BY created_at DESC LIMIT 100 OFFSET ?",
        page * 100L);
  }

  public void append(
      UUID id,
      String actor,
      String action,
      UUID entity,
      String before,
      String after,
      String reason) {
    db.update(
        "INSERT INTO audit_events(id,actor,action,entity_id,before_hash,after_hash,reason) VALUES (?,?,?,?,?,?,?)",
        id,
        actor,
        action,
        entity,
        before,
        after,
        reason);
  }
}
