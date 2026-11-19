package ke.carhire.controllers;

import java.io.IOException;
import java.util.*;
import ke.carhire.models.AttachmentUpload;
import ke.carhire.services.AttachmentService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/attachments")
public class AttachmentController {
  private final AttachmentService service;

  public AttachmentController(AttachmentService service) {
    this.service = service;
  }

  @GetMapping
  List<Map<String, Object>> list(@RequestParam UUID parentId) {
    return service.list(parentId);
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  Map<String, Object> upload(
      @RequestParam UUID parentId,
      @RequestParam(required = false) UUID id,
      @RequestPart MultipartFile file)
      throws IOException {
    return service.upload(
        parentId,
        id,
        new AttachmentUpload(file.getOriginalFilename(), file.getContentType(), file.getBytes()));
  }

  @GetMapping("/{id}")
  ResponseEntity<byte[]> download(@PathVariable UUID id) throws IOException {
    var file = service.download(id);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(file.mime()))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(file.filename()).build().toString())
        .body(file.bytes());
  }

  @GetMapping("/changes")
  Map<String, Object> changes(@RequestParam(defaultValue = "0") long after) {
    return service.changes(after);
  }

  @PostMapping("/cleanup")
  Map<String, Object> cleanup() {
    return service.cleanup();
  }
}
