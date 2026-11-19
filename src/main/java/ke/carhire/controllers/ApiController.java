package ke.carhire.controllers;

import java.util.*;
import ke.carhire.models.*;
import ke.carhire.services.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ApiController {
  private final RecordService records;
  private final SyncService sync;
  private final AuditService audit;
  private final DataHealthService health;

  public ApiController(
      RecordService records, SyncService sync, AuditService audit, DataHealthService health) {
    this.records = records;
    this.sync = sync;
    this.audit = audit;
    this.health = health;
  }

  @GetMapping("/records/{kind}")
  Map<String, Object> list(
      @PathVariable String kind,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(required = false) UUID vehicleId,
      @RequestParam(defaultValue = "") String from,
      @RequestParam(defaultValue = "") String to) {
    return records.list(kind, page, size, q, status, vehicleId, from, to);
  }

  @GetMapping("/records/{kind}/{id}")
  Row get(@PathVariable String kind, @PathVariable UUID id) {
    return records.get(kind, id);
  }

  @PostMapping("/changes")
  Map<String, Object> change(@RequestBody Change change) {
    return records.apply(change);
  }

  @PostMapping("/sync/push")
  Map<String, Object> push(@RequestBody Push push) {
    return sync.push(push);
  }

  @GetMapping("/sync/pull")
  Map<String, Object> pull(
      @RequestParam(defaultValue = "") String cursor,
      @RequestParam(defaultValue = "100") int limit) {
    return sync.pull(cursor, limit);
  }

  @GetMapping("/audit")
  List<Map<String, Object>> audit(@RequestParam(defaultValue = "0") int page) {
    return audit.page(page);
  }

  @GetMapping("/data-health")
  Map<String, Object> health() {
    return health.health();
  }
}
