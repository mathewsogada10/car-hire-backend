package ke.carhire.repositories;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import ke.carhire.exceptions.ApiError;
import ke.carhire.models.Row;
import ke.carhire.utils.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RecordRepository {
  private final JdbcTemplate db;
  private final Json json;

  public RecordRepository(JdbcTemplate db, Json json) {
    this.db = db;
    this.json = json;
  }

  private Row map(java.sql.ResultSet r, int i) throws java.sql.SQLException {
    return new Row(
        r.getObject("id", UUID.class),
        r.getString("kind"),
        r.getLong("revision"),
        json.read(r.getString("data")),
        r.getTimestamp("created_at").toInstant(),
        r.getTimestamp("updated_at").toInstant(),
        r.getTimestamp("deleted_at") == null ? null : r.getTimestamp("deleted_at").toInstant());
  }

  public void lock() {
    db.execute("SELECT pg_advisory_xact_lock(47631001)");
  }

  public Optional<Row> find(UUID id) {
    return db.query("SELECT * FROM records WHERE id=?", this::map, id).stream().findFirst();
  }

  public Row require(UUID id, String kind) {
    Row r = find(id).orElseThrow(() -> ApiError.invalid("Referenced " + kind + " does not exist"));
    if (!r.kind().equals(kind) || r.deletedAt() != null)
      throw ApiError.invalid("Referenced " + kind + " is unavailable");
    return r;
  }

  public List<Row> all(String kind) {
    return db.query(
        "SELECT * FROM records WHERE kind=? AND deleted_at IS NULL ORDER BY id", this::map, kind);
  }

  public List<Row> snapshot() {
    return db.query("SELECT * FROM records ORDER BY kind,id", this::map);
  }

  public List<Row> changes(long cursor, int limit) {
    return db.query(
        "SELECT * FROM records WHERE revision>? ORDER BY revision LIMIT ?",
        this::map,
        cursor,
        limit);
  }

  public Map<String, Object> page(
      String kind,
      int page,
      int size,
      String query,
      String status,
      UUID vehicleId,
      String from,
      String to) {
    StringBuilder where = new StringBuilder(" WHERE kind=? AND deleted_at IS NULL");
    List<Object> args = new ArrayList<>(List.of(kind));
    if (!query.isBlank()) {
      String search =
          "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
      where.append(" AND (data::text ILIKE ?");
      args.add(search);
      if (Set.of("hires", "expenses").contains(kind)) {
        where.append(
            " OR EXISTS (SELECT 1 FROM records vehicle WHERE vehicle.kind='vehicles' AND vehicle.id::text=records.data->>'vehicleId' AND vehicle.data->>'registration' ILIKE ?)");
        args.add(search);
      } else if (kind.equals("payments")) {
        where.append(
            " OR EXISTS (SELECT 1 FROM records hire JOIN records vehicle ON vehicle.id::text=hire.data->>'vehicleId' AND vehicle.kind='vehicles' WHERE hire.kind='hires' AND hire.id::text=records.data->>'hireId' AND vehicle.data->>'registration' ILIKE ?)");
        args.add(search);
      }
      where.append(")");
    }
    if (!status.isBlank()) {
      where.append(" AND data->>'status'=?");
      args.add(status);
    }
    if (vehicleId != null) {
      where.append(" AND data->>'vehicleId'=?");
      args.add(vehicleId.toString());
    }
    if (!from.isBlank()) {
      where.append(" AND COALESCE(data->>'date',left(data->>'plannedStart',10))>=?");
      args.add(from);
    }
    if (!to.isBlank()) {
      where.append(" AND COALESCE(data->>'date',left(data->>'plannedStart',10))<=?");
      args.add(to);
    }
    String amountField = kind.equals("hires") ? "chargeMinor" : "amountMinor";
    boolean financial = Set.of("hires", "payments", "expenses").contains(kind);
    String sum =
        financial ? "COALESCE(sum((data->>'" + amountField + "')::numeric),0)" : "0::numeric";
    Map<String, Object> totals =
        db.queryForMap(
            "SELECT count(*) AS total," + sum + " AS amount FROM records" + where, args.toArray());
    long total = ((Number) totals.get("total")).longValue();
    long amountTotalMinor = ((java.math.BigDecimal) totals.get("amount")).longValueExact();
    args.add(size);
    args.add((long) page * size);
    String order =
        kind.equals("hires")
            ? " ORDER BY (data->>'plannedStart')::timestamptz,id"
            : " ORDER BY updated_at DESC,id";
    List<Row> items =
        db.query(
            "SELECT * FROM records" + where + order + " LIMIT ? OFFSET ?",
            this::map,
            args.toArray());
    return Map.of(
        "items",
        items,
        "references",
        references(items),
        "amountTotalMinor",
        amountTotalMinor,
        "total",
        total,
        "page",
        page,
        "size",
        size);
  }

  private List<Row> references(List<Row> rows) {
    Map<UUID, Row> result = new LinkedHashMap<>();
    List<Row> level = rows;
    for (int depth = 0; depth < 2; depth++) {
      Set<UUID> ids = new LinkedHashSet<>();
      for (Row row : level) {
        if (!Set.of(
                "payments", "hires", "expenses", "maintenance", "readings", "damages", "reminders")
            .contains(row.kind())) continue;
        String field = row.kind().equals("payments") ? "hireId" : "vehicleId";
        String id = row.data().path(field).asText("");
        if (!id.isBlank() && !result.containsKey(UUID.fromString(id))) ids.add(UUID.fromString(id));
      }
      if (ids.isEmpty()) break;
      String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
      level =
          db.query(
              "SELECT * FROM records WHERE id IN (" + placeholders + ")", this::map, ids.toArray());
      level.forEach(row -> result.put(row.id(), row));
    }
    return new ArrayList<>(result.values());
  }

  public Row save(UUID id, String kind, ObjectNode data, Row old, boolean deleted) {
    if (old == null)
      db.update(
          "INSERT INTO records(id,kind,data) VALUES (?,?,?::jsonb)", id, kind, json.write(data));
    else
      db.update(
          "UPDATE records SET data=?::jsonb,revision=nextval('change_revision'),updated_at=now(),deleted_at=? WHERE id=?",
          json.write(data),
          deleted ? java.sql.Timestamp.from(Instant.now()) : null,
          id);
    return find(id).orElseThrow();
  }

  public Long countLive() {
    return db.queryForObject("SELECT count(*) FROM records WHERE deleted_at IS NULL", Long.class);
  }

  public void restoreDeletedAt(UUID id, Instant deletedAt) {
    db.update("UPDATE records SET deleted_at=? WHERE id=?", java.sql.Timestamp.from(deletedAt), id);
  }

  public void purge(UUID id) {
    db.update(
        "UPDATE records SET data='{}'::jsonb,deleted_at=now(),updated_at=now(),revision=nextval('change_revision') WHERE id=?",
        id);
  }
}
