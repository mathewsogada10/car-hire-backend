package ke.carhire.repositories;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OperationRepository {
  private final JdbcTemplate db;

  public OperationRepository(JdbcTemplate db) {
    this.db = db;
  }

  public List<Map<String, Object>> find(UUID id) {
    return db.queryForList("SELECT request_hash,result::text FROM operations WHERE id=?", id);
  }

  public void save(UUID id, String requestHash, String result) {
    db.update(
        "INSERT INTO operations(id,request_hash,result) VALUES (?,?,?::jsonb)",
        id,
        requestHash,
        result);
  }

  public void scrub(UUID id, String tombstone) {
    db.update(
        "UPDATE operations SET result=jsonb_set(result,'{local,data}','{}'::jsonb) WHERE result->'local'->>'id'=?",
        id.toString());
    db.update(
        "UPDATE operations SET result=jsonb_set(result,'{record}',?::jsonb) WHERE result->'record'->>'id'=?",
        tombstone,
        id.toString());
    db.update(
        "UPDATE operations SET result=jsonb_set(jsonb_set(result,'{server}',?::jsonb),'{local,data}','{}'::jsonb) WHERE result->'server'->>'id'=?",
        tombstone,
        id.toString());
  }
}
