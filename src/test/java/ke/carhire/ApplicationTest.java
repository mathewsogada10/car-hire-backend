package ke.carhire;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import ke.carhire.exceptions.ApiError;
import ke.carhire.models.*;
import ke.carhire.repositories.RecordRepository;
import ke.carhire.services.*;
import ke.carhire.utils.Json;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(
    properties = {
      "carhire.password=test-only-owner-password",
      "carhire.storage=target/test-attachments"
    })
@AutoConfigureMockMvc
@Testcontainers
class ApplicationTest {
  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired RecordService service;
  @Autowired RecordRepository store;
  @Autowired Json json;
  @Autowired ReportService reports;
  @Autowired PortableService portable;
  @Autowired PurgeService purge;
  @Autowired MockMvc mvc;
  @Autowired AttachmentService attachments;
  @Autowired AuditService audit;
  @Autowired org.springframework.jdbc.core.JdbcTemplate db;
  @Autowired org.springframework.context.ApplicationContext context;

  @Test
  void attachmentReplayAndExportImportPreserveIdentityAndBytes() throws Exception {
    UUID parent = customer(), id = UUID.randomUUID();
    byte[] content = "%PDF-1.4\nprivate receipt".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var file = new AttachmentUpload("receipt.pdf", "application/pdf", content);
    var first = attachments.upload(parent, id, file);
    assertEquals(id, attachments.upload(parent, id, file).get("id"));
    assertArrayEquals(content, attachments.bytes(id, (String) first.get("sha256")));
    assertThrows(
        ApiError.class,
        () ->
            attachments.upload(
                parent,
                id,
                new AttachmentUpload(
                    "other.pdf", "application/pdf", "%PDF-1.4 changed".getBytes())));
    var p = portable.create(true);
    var preview = portable.ingest(p.bytes(), false, "");
    assertEquals(
        "Ingested",
        portable.ingest(p.bytes(), true, (String) preview.get("packageHash")).get("status"));
  }

  @Test
  void attachmentBearingHireAndItsPaymentsRemainProtectedFromPurge() throws Exception {
    var h =
        create(
            "hires", hire(vehicle(), "Completed", "2010-01-01T10:00:00Z", "2010-01-02T10:00:00Z"));
    var payment = create("payments", payment(h.id(), 100, "Payment"));
    attachments.upload(
        h.id(),
        UUID.randomUUID(),
        new AttachmentUpload("receipt.pdf", "application/pdf", "%PDF-1.4 receipt".getBytes()));
    var p = portable.create(false);
    db.update("UPDATE export_batches SET state='Acknowledged Safe' WHERE id=?", p.id());
    var preview = purge.preview(new PurgeRequest(p.id(), LocalDate.of(2010, 12, 31)));
    assertFalse(((List<?>) preview.get("ids")).contains(h.id().toString()));
    assertFalse(((List<?>) preview.get("ids")).contains(payment.id().toString()));
  }

  ObjectNode data(String value) {
    return (ObjectNode) json.read(value);
  }

  Row create(String kind, ObjectNode data) {
    UUID id = UUID.randomUUID();
    return save(id, kind, 0, data);
  }

  Row save(UUID id, String kind, long revision, ObjectNode data) {
    var result =
        service.apply(new Change(UUID.randomUUID(), id, kind, revision, data, false, null));
    assertNotEquals("Conflict", result.get("outcome"));
    return json.convert(result.get("record"), Row.class);
  }

  UUID vehicle() {
    return create(
            "vehicles",
            data(
                "{\"registration\":\""
                    + UUID.randomUUID()
                    + "\",\"make\":\"Toyota\",\"model\":\"Axio\",\"status\":\"Available\"}"))
        .id();
  }

  UUID customer() {
    return create("customers", data("{\"name\":\"Test customer\",\"phone\":\"0700000000\"}")).id();
  }

  ObjectNode hire(UUID vehicle, String status, String start, String end) {
    var d = data("{\"chargeMinor\":4000000}");
    d.put("vehicleId", vehicle.toString());
    d.put("customerId", customer().toString());
    d.put("status", status);
    d.put("plannedStart", start);
    d.put("plannedEnd", end);
    if (status.equals("Active") || status.equals("Completed")) d.put("actualStart", start);
    if (status.equals("Completed")) d.put("actualEnd", end);
    return d;
  }

  ObjectNode payment(UUID hire, long amount, String kind) {
    var d = data("{\"status\":\"Received\",\"method\":\"Cash\",\"date\":\"2026-01-03\"}");
    d.put("hireId", hire.toString());
    d.put("amountMinor", amount);
    d.put("kind", kind);
    return d;
  }

  @Test
  void financesSeparateChargesCashAndExpenseDates() throws Exception {
    UUID v = vehicle();
    var h = create("hires", hire(v, "Completed", "2026-01-01T10:00:00Z", "2026-01-03T10:00:00Z"));
    create("payments", payment(h.id(), 1500000, "Payment"));
    UUID category = create("categories", data("{\"name\":\"Service\"}")).id();
    var expense =
        data(
            "{\"name\":\"Repairs\",\"amountMinor\":800000,\"status\":\"Paid\",\"date\":\"2026-01-02\",\"paidDate\":\"2026-01-03\"}");
    expense.put("vehicleId", v.toString());
    expense.put("categoryId", category.toString());
    create("expenses", expense);
    var r = reports.calculate(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), v);
    assertEquals(2500000L, r.get("outstandingMinor"));
    assertEquals(3200000L, r.get("expectedProfitMinor"));
    assertEquals(700000L, r.get("cashProfitMinor"));
    mvc.perform(
            get("/api/v1/reports")
                .with(user("owner").roles("OWNER"))
                .param("from", "2026-01-01")
                .param("to", "2026-01-31")
                .param("vehicleId", v.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.expectedProfitMinor").value(3200000));
    mvc.perform(
            get("/api/v1/reports/balances.csv")
                .with(user("owner").roles("OWNER"))
                .param("from", "2026-01-01")
                .param("to", "2026-01-31")
                .param("vehicleId", v.toString()))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    "hire_id,vehicle_id,customer_id,charge_minor,allocated_minor,outstanding_minor\r\n"
                        + h.id()
                        + ","
                        + v
                        + ","
                        + h.data().path("customerId").asText()
                        + ",4000000,1500000,2500000\r\n"));
    assertEquals(
        0L,
        reports
            .calculate(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2), v)
            .get("recognizedMinor"));
  }

  @Test
  void hirePagesAreChronologicalBeforePaginationAndDefinedStatusFiltersCombineWithDates()
      throws Exception {
    String marker = "hire-filter-" + UUID.randomUUID();
    var rows = new ArrayList<Row>();
    for (String status : List.of("Draft", "Booked", "Active", "Completed", "Cancelled")) {
      int day = 5 - rows.size();
      var d =
          hire(
              vehicle(),
              status,
              "2014-01-0" + day + "T10:00:00Z",
              "2014-01-0" + (day + 1) + "T10:00:00Z");
      d.put("notes", marker);
      rows.add(create("hires", d));
    }
    rows.sort(
        Comparator.comparing((Row row) -> Instant.parse(row.data().path("plannedStart").asText()))
            .thenComparing(row -> row.id().toString()));
    for (int page = 0; page < 3; page++) {
      var result =
          mvc.perform(
                  get("/api/v1/records/hires")
                      .with(user("owner").roles("OWNER"))
                      .param("q", marker)
                      .param("size", "2")
                      .param("page", String.valueOf(page)))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.total").value(5))
              .andExpect(jsonPath("$.items.length()").value(Math.min(2, 5 - page * 2)));
      for (int item = 0; item < Math.min(2, 5 - page * 2); item++) {
        result.andExpect(
            jsonPath("$.items[" + item + "].id").value(rows.get(page * 2 + item).id().toString()));
      }
    }
    for (Row row : rows) {
      String status = row.data().path("status").asText();
      mvc.perform(
              get("/api/v1/records/hires")
                  .with(user("owner").roles("OWNER"))
                  .param("q", marker)
                  .param("status", status)
                  .param("from", "2014-01-01")
                  .param("to", "2014-01-05"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.total").value(1))
          .andExpect(jsonPath("$.items[0].id").value(row.id().toString()))
          .andExpect(jsonPath("$.items[0].data.status").value(status));
    }
  }

  @Test
  void adjacentBookingsAllowedAndOverlapsRejected() {
    UUID v = vehicle();
    create("hires", hire(v, "Booked", "2027-01-01T00:00:00Z", "2027-01-03T00:00:00Z"));
    create("hires", hire(v, "Booked", "2027-01-03T00:00:00Z", "2027-01-04T00:00:00Z"));
    assertThrows(
        ApiError.class,
        () -> create("hires", hire(v, "Booked", "2027-01-02T00:00:00Z", "2027-01-04T00:00:00Z")));
  }

  @Test
  void refundsCannotExceedOriginalAndDepositsStaySeparate() {
    UUID v = vehicle();
    var h = create("hires", hire(v, "Booked", "2028-01-01T00:00:00Z", "2028-01-02T00:00:00Z"));
    var deposit = create("payments", payment(h.id(), 100000, "Deposit"));
    assertEquals(
        0L,
        reports
            .calculate(LocalDate.of(2026, 1, 1), LocalDate.of(2030, 1, 1), v)
            .get("collectionsMinor"));
    var refund = payment(h.id(), 100001, "DepositRefund");
    refund.put("originalPaymentId", deposit.id().toString());
    assertThrows(ApiError.class, () -> create("payments", refund));
    create("payments", payment(h.id(), 100000, "DepositApplication"));
    assertThrows(
        ApiError.class, () -> create("payments", payment(h.id(), 1, "DepositApplication")));
  }

  @Test
  void replayIsDurableAndConflictContainsBothVersions() {
    UUID id = UUID.randomUUID(), op = UUID.randomUUID();
    var c = new Change(op, id, "customers", 0, data("{\"name\":\"One\"}"), false, null);
    var first = service.apply(c);
    assertEquals(json.hash(first), json.hash(service.apply(c)));
    assertThrows(
        ApiError.class,
        () ->
            service.apply(
                new Change(op, id, "customers", 0, data("{\"name\":\"Other\"}"), false, null)));
    var conflict =
        service.apply(
            new Change(
                UUID.randomUUID(), id, "customers", 0, data("{\"name\":\"Two\"}"), false, null));
    assertEquals("Conflict", conflict.get("outcome"));
    assertNotNull(conflict.get("server"));
    assertNotNull(conflict.get("local"));
  }

  @Test
  void tombstoneCannotBeResurrected() {
    var c = create("customers", data("{\"name\":\"Delete me\"}"));
    service.apply(
        new Change(
            UUID.randomUUID(), c.id(), c.kind(), c.revision(), (ObjectNode) c.data(), true, null));
    var row = store.find(c.id()).orElseThrow();
    assertEquals(
        "Conflict",
        service
            .apply(
                new Change(
                    UUID.randomUUID(),
                    c.id(),
                    c.kind(),
                    row.revision(),
                    data("{\"name\":\"Resurrection\"}"),
                    false,
                    null))
            .get("outcome"));
  }

  @Test
  void enabledRemindersRequireConfiguration() {
    var d =
        data(
            "{\"currency\":\"KES\",\"timeZone\":\"Africa/Nairobi\",\"bufferMinutes\":0,\"remindersEnabled\":true}");
    assertThrows(ApiError.class, () -> save(DomainPolicy.SETTINGS_ID, "settings", 0, d));
  }

  @Test
  void exportVerifiesCorruptionFailsAndImportReplayHasNoDuplicates() throws Exception {
    create(
        "vehicles",
        data(
            "{\"registration\":\"EXPORT"
                + UUID.randomUUID()
                + "\",\"make\":\"Toyota\",\"model\":\"Test\",\"status\":\"Available\"}"));
    var p = portable.create(false);
    var verified = portable.verify(p.bytes());
    assertEquals(p.id().toString(), verified.manifest().path("exportBatchId").asText());
    var preview = portable.ingest(p.bytes(), false, "");
    long count = db.queryForObject("SELECT count(*) FROM records", Long.class);
    var result = portable.ingest(p.bytes(), true, (String) preview.get("packageHash"));
    assertEquals("Ingested", result.get("status"));
    assertEquals(
        json.hash(result),
        json.hash(portable.ingest(p.bytes(), true, (String) preview.get("packageHash"))));
    assertEquals(count, db.queryForObject("SELECT count(*) FROM records", Long.class));
    byte[] corrupt = p.bytes().clone();
    Arrays.fill(corrupt, 0, Math.min(1000, corrupt.length), (byte) 0);
    assertThrows(Exception.class, () -> portable.verify(corrupt));
  }

  @Test
  void zipTraversalRejected() throws Exception {
    var bytes = new java.io.ByteArrayOutputStream();
    try (var z = new java.util.zip.ZipOutputStream(bytes)) {
      z.putNextEntry(new java.util.zip.ZipEntry("../escape"));
      z.write(1);
      z.closeEntry();
    }
    assertThrows(ApiError.class, () -> portable.verify(bytes.toByteArray()));
  }

  @Test
  void androidProducedPortableFixtureImportsAndReplays() throws Exception {
    byte[] bytes;
    try (var input =
        Objects.requireNonNull(getClass().getResourceAsStream("/contracts/android-v1.zip"))) {
      bytes = input.readAllBytes();
    }
    var source = portable.verify(bytes);
    assertEquals("car-hire-android", source.manifest().path("sourceApp").asText());
    var preview = portable.ingest(bytes, false, "");
    var result = portable.ingest(bytes, true, (String) preview.get("packageHash"));
    assertEquals("Ingested", result.get("status"));
    assertEquals(source.rows().size(), result.get("inserted"));
    for (var row : source.rows())
      assertEquals(row.data(), store.require(row.id(), row.kind()).data());
    assertEquals(
        result.get("importBatchId").toString(),
        portable
            .ingest(bytes, true, (String) preview.get("packageHash"))
            .get("importBatchId")
            .toString());
  }

  @Test
  void checksummedButFalseFinancialTotalsAreRejected() throws Exception {
    var p = portable.create(false);
    Map<String, byte[]> files = new LinkedHashMap<>();
    try (var input =
        new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(p.bytes()))) {
      java.util.zip.ZipEntry entry;
      while ((entry = input.getNextEntry()) != null)
        files.put(entry.getName(), input.readAllBytes());
    }
    var manifest =
        (ObjectNode)
            json.read(
                new String(files.get("manifest.json"), java.nio.charset.StandardCharsets.UTF_8));
    ((ObjectNode) manifest.path("financialTotals"))
        .put(
            "recognizedMinor",
            manifest.path("financialTotals").path("recognizedMinor").asLong() + 1);
    files.put(
        "manifest.json", json.write(manifest).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var checksums = new StringBuilder();
    files.forEach(
        (name, bytes) -> {
          if (!name.equals("checksums.sha256"))
            checksums.append(Json.hash(bytes)).append("  ").append(name).append('\n');
        });
    files.put(
        "checksums.sha256", checksums.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var output = new java.io.ByteArrayOutputStream();
    try (var zip = new java.util.zip.ZipOutputStream(output)) {
      for (var entry : files.entrySet()) {
        zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
    }
    assertThrows(ApiError.class, () -> portable.verify(output.toByteArray()));
  }

  @Test
  void purgeNeedsSafeExportAndFreshPreview() throws Exception {
    UUID v = vehicle();
    create("hires", hire(v, "Completed", "2020-01-01T00:00:00Z", "2020-01-02T00:00:00Z"));
    var p = portable.create(false);
    var request = new PurgeRequest(p.id(), LocalDate.of(2020, 12, 31));
    assertThrows(ApiError.class, () -> purge.preview(request));
    db.update("UPDATE export_batches SET state='Acknowledged Safe' WHERE id=?", p.id());
    var preview = purge.preview(request);
    assertTrue((Integer) preview.get("count") > 0);
    assertThrows(
        ApiError.class,
        () ->
            purge.commit(new PurgeConfirmation(request, "wrong", "PURGE " + preview.get("count"))));
    var result =
        purge.commit(
            new PurgeConfirmation(
                request, (String) preview.get("previewHash"), "PURGE " + preview.get("count")));
    assertEquals(preview.get("count"), result.get("purged"));
    assertNotNull(store.require(v, "vehicles"));
  }

  @Test
  void concurrentBookingsOnlyOneWins() throws Exception {
    UUID v = vehicle();
    var a = hire(v, "Booked", "2029-01-01T00:00:00Z", "2029-01-04T00:00:00Z");
    var b = a.deepCopy();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var start = new CountDownLatch(1);
      Callable<Boolean> first =
          () -> {
            start.await();
            try {
              create("hires", a);
              return true;
            } catch (ApiError e) {
              return false;
            }
          };
      Callable<Boolean> second =
          () -> {
            start.await();
            try {
              create("hires", b);
              return true;
            } catch (ApiError e) {
              return false;
            }
          };
      var x = executor.submit(first);
      var y = executor.submit(second);
      start.countDown();
      assertNotEquals(x.get(), y.get());
    }
  }

  @Test
  void apiRequiresAuthenticationAndCsrf() throws Exception {
    mvc.perform(get("/api/v1/records/vehicles")).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/changes")
                .with(user("owner").roles("OWNER"))
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/records/vehicles").with(user("owner").roles("OWNER")))
        .andExpect(status().isOk());
    mvc.perform(get("/v3/api-docs").with(user("owner").roles("OWNER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components.schemas.Change.properties.data.type").value("object"))
        .andExpect(
            jsonPath("$.components.schemas.Change.properties.data.additionalProperties")
                .value(true))
        .andExpect(jsonPath("$.components.schemas.Row.properties.data.type").value("object"));
  }

  @Test
  void controllersDependOnServicesAndServicesDoNotExposeJdbc() {
    for (Object bean :
        context
            .getBeansWithAnnotation(org.springframework.web.bind.annotation.RestController.class)
            .values()) {
      Class<?> type = org.springframework.aop.support.AopUtils.getTargetClass(bean);
      if (!type.getPackageName().startsWith("ke.carhire")) continue;
      assertEquals("ke.carhire.controllers", type.getPackageName());
      for (var constructor : type.getDeclaredConstructors())
        for (var dependency : constructor.getParameterTypes())
          assertEquals(
              "ke.carhire.services",
              dependency.getPackageName(),
              type.getSimpleName() + " must delegate to services");
    }
    for (Object bean :
        context.getBeansWithAnnotation(org.springframework.stereotype.Service.class).values()) {
      Class<?> type = org.springframework.aop.support.AopUtils.getTargetClass(bean);
      for (var field : type.getDeclaredFields()) {
        assertFalse(
            org.springframework.jdbc.core.JdbcTemplate.class.isAssignableFrom(field.getType()),
            type.getSimpleName() + " must use repositories");
        assertFalse(
            javax.sql.DataSource.class.isAssignableFrom(field.getType()),
            type.getSimpleName() + " must use repositories");
      }
    }
  }

  @Test
  void recordAndSyncHttpContractsSurviveLayeredTransactions() throws Exception {
    UUID id = UUID.randomUUID();
    String name = "Layered customer " + id;
    var good =
        new Change(
            UUID.randomUUID(),
            id,
            "customers",
            0,
            data("{\"name\":\"" + name + "\"}"),
            false,
            null);
    var bad =
        new Change(
            UUID.randomUUID(), UUID.randomUUID(), "customers", 0, json.object(), false, null);
    String body = json.write(new Push(List.of(good, bad), "mvc-regression"));
    String first =
        mvc.perform(
                post("/api/v1/sync/push")
                    .with(user("owner").roles("OWNER"))
                    .with(csrf())
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.results[0].outcome").value("Accepted"))
            .andExpect(jsonPath("$.results[1].outcome").value("Rejected"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String replay =
        mvc.perform(
                post("/api/v1/sync/push")
                    .with(user("owner").roles("OWNER"))
                    .with(csrf())
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertEquals(json.read(first), json.read(replay));
    assertTrue(store.find(bad.id()).isEmpty());
    mvc.perform(get("/api/v1/records/customers/" + id).with(user("owner").roles("OWNER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value(name));
    mvc.perform(
            get("/api/v1/records/customers").param("q", name).with(user("owner").roles("OWNER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));
    String cursor =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                Long.toString(store.find(id).orElseThrow().revision() - 1)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    mvc.perform(
            get("/api/v1/sync/pull")
                .param("cursor", cursor)
                .param("limit", "1")
                .with(user("owner").roles("OWNER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(id.toString()));
    mvc.perform(
            post("/api/v1/changes")
                .with(user("owner").roles("OWNER"))
                .with(csrf())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Invalid request format"));
  }

  @Test
  void attachmentHttpAdapterPreservesMetadataAndBytes() throws Exception {
    UUID parent = customer(), id = UUID.randomUUID();
    byte[] bytes = "%PDF-1.4 HTTP adapter".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var file =
        new org.springframework.mock.web.MockMultipartFile(
            "file", "http.pdf", "application/pdf", bytes);
    mvc.perform(
            multipart("/api/v1/attachments")
                .file(file)
                .param("parentId", parent.toString())
                .param("id", id.toString())
                .with(user("owner").roles("OWNER"))
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id.toString()));
    mvc.perform(
            get("/api/v1/attachments")
                .param("parentId", parent.toString())
                .with(user("owner").roles("OWNER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].parent_id").value(parent.toString()));
    mvc.perform(get("/api/v1/attachments/" + id).with(user("owner").roles("OWNER")))
        .andExpect(status().isOk())
        .andExpect(content().contentType("application/pdf"))
        .andExpect(content().bytes(bytes));
    long revision =
        db.queryForObject("SELECT revision FROM attachments WHERE id=?", Long.class, id);
    mvc.perform(
            get("/api/v1/attachments/changes")
                .param("after", Long.toString(revision - 1))
                .with(user("owner").roles("OWNER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].parentId").value(parent.toString()));
  }

  @Test
  void exportAcknowledgementStillRequiresRecentReauthentication() throws Exception {
    var exported = portable.create(false);
    String confirmation =
        json.write(
            Map.of(
                "confirmation",
                "I VERIFIED AND SAFELY STORED THIS EXPORT",
                "destinationNote",
                "Test vault"));
    mvc.perform(
            post("/api/v1/exports/" + exported.id() + "/acknowledge")
                .with(user("owner").roles("OWNER"))
                .with(csrf())
                .contentType("application/json")
                .content(confirmation))
        .andExpect(status().isForbidden());
    var session = new org.springframework.mock.web.MockHttpSession();
    mvc.perform(
            post("/api/v1/auth/reauth")
                .session(session)
                .with(user("owner").roles("OWNER"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"password\":\"test-only-owner-password\"}"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/auth/me").with(user("owner").roles("OWNER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("owner"));
    mvc.perform(
            post("/api/v1/exports/" + exported.id() + "/acknowledge")
                .session(session)
                .with(user("owner").roles("OWNER"))
                .with(csrf())
                .contentType("application/json")
                .content(confirmation))
        .andExpect(status().isOk());
    assertEquals(
        "Acknowledged Safe",
        db.queryForObject(
            "SELECT state FROM export_batches WHERE id=?", String.class, exported.id()));
    mvc.perform(get("/api/v1/data-health").with(user("owner").roles("OWNER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lastExport[0].id").value(exported.id().toString()));
  }

  @Test
  void auditIsImmutable() {
    audit.record("TEST", UUID.randomUUID(), null, Map.of("safe", true), null);
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.update("DELETE FROM audit_events WHERE action='TEST'"));
  }
}
