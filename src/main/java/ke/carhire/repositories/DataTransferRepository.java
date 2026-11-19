package ke.carhire.repositories;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DataTransferRepository {
  private final JdbcTemplate db;

  public DataTransferRepository(JdbcTemplate db) {
    this.db = db;
  }

  public void saveExport(UUID id, String manifest, String snapshot) {
    db.update(
        "INSERT INTO export_batches(id,state,manifest,snapshot) VALUES (?,'Locally Verified',?::jsonb,?::jsonb)",
        id,
        manifest,
        snapshot);
  }

  public List<Map<String, Object>> exports() {
    return db.queryForList(
        "SELECT id,state,manifest,created_at FROM export_batches ORDER BY created_at DESC LIMIT 100");
  }

  public List<Map<String, Object>> exportEvidence(UUID id) {
    return db.queryForList(
        "SELECT state,snapshot::text,manifest::text FROM export_batches WHERE id=?", id);
  }

  public int acknowledge(UUID id) {
    return db.update(
        "UPDATE export_batches SET state='Acknowledged Safe' WHERE id=? AND state='Locally Verified'",
        id);
  }

  public List<Map<String, Object>> lastSafeExport() {
    return db.queryForList(
        "SELECT id,state,created_at FROM export_batches WHERE state IN ('Acknowledged Safe','Ingested') ORDER BY created_at DESC LIMIT 1");
  }

  public List<Map<String, Object>> findImport(UUID source) {
    return db.queryForList(
        "SELECT package_hash,result::text FROM import_batches WHERE source_id=?", source);
  }

  public void saveImport(UUID id, UUID source, String hash, String result) {
    db.update(
        "INSERT INTO import_batches(id,source_id,package_hash,result) VALUES (?,?,?,?::jsonb)",
        id,
        source,
        hash,
        result);
  }

  public void markIngested(UUID source) {
    db.update("UPDATE export_batches SET state='Ingested' WHERE id=?", source);
  }

  public void savePurge(UUID id, UUID export, String previewHash, String outcome) {
    db.update(
        "INSERT INTO purge_batches(id,export_id,preview_hash,outcome) VALUES (?,?,?,?::jsonb)",
        id,
        export,
        previewHash,
        outcome);
  }
}
