package ke.carhire.controllers;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import ke.carhire.models.*;
import ke.carhire.services.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class DataController {
  private final PortableService portable;
  private final ExportHistoryService history;
  private final PurgeService purge;
  private final AuthService auth;

  public DataController(
      PortableService portable,
      ExportHistoryService history,
      PurgeService purge,
      AuthService auth) {
    this.portable = portable;
    this.history = history;
    this.purge = purge;
    this.auth = auth;
  }

  @PostMapping("/exports")
  ResponseEntity<byte[]> export(@RequestParam(defaultValue = "false") boolean includeAttachments)
      throws IOException {
    var p = portable.create(includeAttachments);
    return ResponseEntity.ok()
        .header("X-Export-Batch-Id", p.id().toString())
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=car-hire-export_"
                + java.time.LocalDate.now(ReportService.ZONE)
                + "_"
                + p.id()
                + ".zip")
        .contentType(MediaType.parseMediaType("application/zip"))
        .body(p.bytes());
  }

  @GetMapping("/exports")
  List<Map<String, Object>> exports() {
    return history.list();
  }

  @PostMapping("/exports/{id}/acknowledge")
  void acknowledge(
      @PathVariable UUID id, @RequestBody Map<String, String> body, HttpServletRequest request) {
    auth.requireRecent(request.getSession().getAttribute("reauth"));
    history.acknowledge(id, body);
  }

  @PostMapping(value = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  Map<String, Object> ingest(
      @RequestPart MultipartFile file,
      @RequestParam(defaultValue = "false") boolean commit,
      @RequestParam(defaultValue = "") String packageHash)
      throws IOException {
    return portable.ingest(file.getBytes(), commit, packageHash);
  }

  @PostMapping("/purge/preview")
  Map<String, Object> preview(@RequestBody PurgeRequest request) {
    return purge.preview(request);
  }

  @PostMapping("/purge/commit")
  Map<String, Object> commit(
      @RequestBody PurgeConfirmation confirmation, HttpServletRequest request) {
    auth.requireRecent(request.getSession().getAttribute("reauth"));
    return purge.commit(confirmation);
  }
}
