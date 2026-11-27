package ke.carhire.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import ke.carhire.exceptions.ApiError;
import ke.carhire.models.*;
import ke.carhire.repositories.*;
import org.springframework.stereotype.Component;

@Component
public class DomainPolicy {
  public static final UUID SETTINGS_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
  static final Set<String> KINDS =
      Set.of(
          "vehicles",
          "customers",
          "hires",
          "payments",
          "expenses",
          "categories",
          "providers",
          "maintenance",
          "reminders",
          "readings",
          "conditions",
          "damages",
          "settings");
  private final RecordRepository store;

  public DomainPolicy(RecordRepository store) {
    this.store = store;
  }

  static String text(JsonNode d, String key) {
    return d.path(key).asText("").trim();
  }

  static String required(JsonNode d, String key) {
    String v = text(d, key);
    if (v.isEmpty() || v.length() > 2000)
      throw ApiError.invalid(key + " is required (maximum 2000 characters)");
    return v;
  }

  static long money(JsonNode d, String key) {
    JsonNode n = d.path(key);
    if (!n.isIntegralNumber()
        || !n.canConvertToLong()
        || n.longValue() < 0
        || n.longValue() > 1_000_000_000_000L)
      throw ApiError.invalid(
          key + " must be an integer minor-unit amount between 0 and 1000000000000");
    return n.longValue();
  }

  static UUID uuid(JsonNode d, String key) {
    try {
      return UUID.fromString(required(d, key));
    } catch (IllegalArgumentException e) {
      throw ApiError.invalid(key + " must be a UUID");
    }
  }

  static LocalDate date(JsonNode d, String key) {
    try {
      return LocalDate.parse(required(d, key));
    } catch (DateTimeException e) {
      throw ApiError.invalid(key + " must be YYYY-MM-DD");
    }
  }

  static Instant instant(JsonNode d, String key) {
    try {
      return Instant.parse(required(d, key));
    } catch (DateTimeException e) {
      throw ApiError.invalid(key + " must be a UTC instant");
    }
  }

  static void choice(JsonNode d, String key, String... values) {
    if (!List.of(values).contains(required(d, key)))
      throw ApiError.invalid(key + " must be one of " + String.join(", ", values));
  }

  Row ref(JsonNode d, String key, String kind) {
    return store.require(uuid(d, key), kind);
  }

  void validate(String kind, UUID id, ObjectNode d, Row old) {
    if (!KINDS.contains(kind)) throw ApiError.invalid("Unknown record type");
    if (d.toString().length() > 32000) throw ApiError.invalid("Record exceeds size limit");
    switch (kind) {
      case "vehicles" -> {
        d.put(
            "registration",
            required(d, "registration").replaceAll("\\s+", "").toUpperCase(Locale.ROOT));
        required(d, "make");
        required(d, "model");
        choice(d, "status", "Available", "Hired", "Under Service", "Inactive", "Sold");
        if (d.has("odometerKm")) {
          long km = money(d, "odometerKm");
          if (old != null && km < old.data().path("odometerKm").asLong())
            required(d, "correctionReason");
        }
        if (List.of("Inactive", "Sold").contains(text(d, "status"))
            && store.all("hires").stream()
                .anyMatch(
                    h ->
                        text(h.data(), "vehicleId").equals(id.toString())
                            && List.of("Booked", "Active").contains(text(h.data(), "status"))))
          throw ApiError.invalid("Vehicle has a booked or active hire");
      }
      case "customers" -> required(d, "name");
      case "categories", "providers" -> required(d, "name");
      case "hires" -> hire(id, d, old);
      case "payments" -> payment(id, d, old);
      case "expenses" -> {
        ref(d, "vehicleId", "vehicles");
        ref(d, "categoryId", "categories");
        required(d, "name");
        money(d, "amountMinor");
        date(d, "date");
        choice(d, "status", "Planned", "Due", "Paid", "Cancelled");
        if (text(d, "status").equals("Paid")) date(d, "paidDate");
        if (!text(d, "providerId").isEmpty()) ref(d, "providerId", "providers");
      }
      case "maintenance" -> {
        ref(d, "vehicleId", "vehicles");
        required(d, "name");
        date(d, "date");
        Instant start = instant(d, "start"), end = instant(d, "end");
        if (!end.isAfter(start)) throw ApiError.invalid("Service end must follow start");
        overlap(uuid(d, "vehicleId"), start, end, id);
        if (!text(d, "expenseId").isEmpty()) {
          var expense = ref(d, "expenseId", "expenses");
          if (!text(expense.data(), "vehicleId").equals(text(d, "vehicleId")))
            throw ApiError.invalid("Maintenance expense belongs to another vehicle");
        }
      }
      case "reminders" -> {
        required(d, "name");
        choice(d, "status", "Open", "Snoozed", "Completed", "Dismissed");
        if (text(d, "dueDate").isEmpty() && !d.has("dueKm"))
          throw ApiError.invalid("A due date or mileage is required");
        if (!text(d, "dueDate").isEmpty()) date(d, "dueDate");
        if (d.has("dueKm")) {
          money(d, "dueKm");
          ref(d, "vehicleId", "vehicles");
        }
        if (text(d, "status").equals("Snoozed")) date(d, "snoozedUntil");
      }
      case "readings" -> {
        ref(d, "vehicleId", "vehicles");
        choice(d, "type", "Odometer", "Fuel");
        money(d, "value");
        date(d, "date");
        if (text(d, "type").equals("Odometer")) {
          long latest =
              store.all("readings").stream()
                  .filter(
                      r ->
                          !r.id().equals(id)
                              && text(r.data(), "vehicleId").equals(text(d, "vehicleId"))
                              && text(r.data(), "type").equals("Odometer"))
                  .mapToLong(r -> r.data().path("value").asLong())
                  .max()
                  .orElse(0);
          if (d.path("value").asLong() < latest) required(d, "correctionReason");
        }
      }
      case "conditions" -> {
        ref(d, "hireId", "hires");
        choice(d, "phase", "PRE", "POST");
        money(d, "odometerKm");
        required(d, "notes");
        date(d, "date");
      }
      case "damages" -> {
        var hire = ref(d, "hireId", "hires");
        ref(d, "vehicleId", "vehicles");
        if (!text(hire.data(), "vehicleId").equals(text(d, "vehicleId")))
          throw ApiError.invalid("Damage vehicle does not match hire");
        required(d, "name");
        money(d, "amountMinor");
        choice(d, "status", "Open", "Charged", "Waived", "Settled");
        choice(d, "responsibility", "Customer", "Business", "Other");
        date(d, "date");
      }
      case "settings" -> {
        if (!id.equals(SETTINGS_ID)) throw ApiError.invalid("Use the singleton settings ID");
        if (!text(d, "currency").equals("KES") || !text(d, "timeZone").equals("Africa/Nairobi"))
          throw ApiError.invalid("This version requires KES and Africa/Nairobi");
        long buffer = money(d, "bufferMinutes");
        if (buffer > 1440) throw ApiError.invalid("Buffer cannot exceed 24 hours");
        if (!d.path("remindersEnabled").isBoolean())
          throw ApiError.invalid("remindersEnabled must be boolean");
        if (d.path("remindersEnabled").asBoolean()) {
          long days = money(d, "reminderLeadDays");
          if (days > 365) throw ApiError.invalid("Reminder lead time cannot exceed 365 days");
          required(d, "reminderTime");
          try {
            LocalTime.parse(text(d, "reminderTime"));
          } catch (DateTimeException e) {
            throw ApiError.invalid("Reminder time must be HH:mm");
          }
          if (money(d, "backupReminderDays") < 1)
            throw ApiError.invalid("Backup reminder interval must be positive");
        }
      }
      default -> throw ApiError.invalid("Unsupported type");
    }
  }

  void hire(UUID id, ObjectNode d, Row old) {
    var vehicle = ref(d, "vehicleId", "vehicles");
    var customer = ref(d, "customerId", "customers");
    var start = instant(d, "plannedStart");
    var end = instant(d, "plannedEnd");
    if (!end.isAfter(start)) throw ApiError.invalid("Return must follow pickup");
    money(d, "chargeMinor");
    choice(d, "status", "Draft", "Booked", "Active", "Completed", "Cancelled");
    String status = text(d, "status");
    if (d.path("chargeMinor").asLong() == 0 && !d.path("zeroChargeConfirmed").asBoolean())
      throw ApiError.invalid("Confirm a zero-charge hire explicitly");
    if (old != null) {
      String prior = text(old.data(), "status");
      Map<String, List<String>> transitions =
          Map.of(
              "Draft",
              List.of("Draft", "Booked", "Cancelled"),
              "Booked",
              List.of("Booked", "Active", "Cancelled"),
              "Active",
              List.of("Active", "Completed"),
              "Completed",
              List.of("Completed"),
              "Cancelled",
              List.of("Cancelled"));
      if (!transitions.get(prior).contains(status))
        throw ApiError.invalid("Invalid hire transition " + prior + " -> " + status);
    }
    if (List.of("Booked", "Active").contains(status)) {
      if (List.of("Inactive", "Sold", "Under Service").contains(text(vehicle.data(), "status")))
        throw ApiError.invalid("Vehicle is not operational");
      if (text(customer.data(), "phone").isEmpty() && text(customer.data(), "email").isEmpty())
        throw ApiError.invalid("Customer needs a usable contact before booking");
    }
    if (status.equals("Active") || status.equals("Completed")) {
      start = instant(d, "actualStart");
      if (status.equals("Active") && !end.isAfter(start)) end = Instant.MAX.minusSeconds(86400);
    }
    if (status.equals("Completed")) {
      var actualEnd = instant(d, "actualEnd");
      if (actualEnd.isBefore(instant(d, "actualStart")))
        throw ApiError.invalid("Actual return precedes pickup");
      end = actualEnd;
      start = instant(d, "actualStart");
    }
    if (!List.of("Cancelled", "Draft").contains(status))
      overlap(uuid(d, "vehicleId"), start, end, id);
  }

  void overlap(UUID vehicle, Instant start, Instant end, UUID exclude) {
    long buffer =
        store.find(SETTINGS_ID).map(r -> r.data().path("bufferMinutes").asLong()).orElse(0L) * 60;
    for (var r : store.all("hires")) {
      var h = r.data();
      if (r.id().equals(exclude)
          || !text(h, "vehicleId").equals(vehicle.toString())
          || List.of("Draft", "Cancelled").contains(text(h, "status"))) continue;
      Instant hs =
          text(h, "actualStart").isEmpty() ? instant(h, "plannedStart") : instant(h, "actualStart");
      Instant he =
          text(h, "actualEnd").isEmpty() ? instant(h, "plannedEnd") : instant(h, "actualEnd");
      if (text(h, "status").equals("Active") && he.isBefore(Instant.now()))
        he = Instant.MAX.minusSeconds(buffer);
      if (start.isBefore(he.plusSeconds(buffer)) && hs.isBefore(end.plusSeconds(buffer))) {
        String summary = hireConflictSummary(h);
        throw new ApiError(
            org.springframework.http.HttpStatus.CONFLICT,
            "Vehicle has an overlapping hire: " + summary,
            Map.of("conflictingHire", summary));
      }
    }
    for (var r : store.all("maintenance"))
      if (!r.id().equals(exclude)
          && text(r.data(), "vehicleId").equals(vehicle.toString())
          && start.isBefore(instant(r.data(), "end").plusSeconds(buffer))
          && instant(r.data(), "start").isBefore(end.plusSeconds(buffer)))
        throw new ApiError(
            org.springframework.http.HttpStatus.CONFLICT,
            "Vehicle has a service window",
            Map.of("conflictingId", r.id()));
  }

  private static String hireConflictSummary(JsonNode data) {
    Instant start =
        instant(data, text(data, "actualStart").isEmpty() ? "plannedStart" : "actualStart");
    Instant end = instant(data, text(data, "actualEnd").isEmpty() ? "plannedEnd" : "actualEnd");
    Duration duration = Duration.between(start, end);
    long days =
        duration.isNegative() || duration.isZero()
            ? 0
            : duration.toDays() + (duration.minusDays(duration.toDays()).isZero() ? 0 : 1);
    var format =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ReportService.ZONE);
    return format.format(start)
        + " - "
        + format.format(end)
        + " | Days:"
        + days
        + " | "
        + String.format(
            Locale.ROOT, "KES %,.2f", BigDecimal.valueOf(data.path("chargeMinor").asLong(), 2));
  }

  void payment(UUID id, ObjectNode d, Row old) {
    ref(d, "hireId", "hires");
    long amount = money(d, "amountMinor");
    if (amount == 0) throw ApiError.invalid("Payment must be positive");
    date(d, "date");
    required(d, "method");
    choice(d, "status", "Pending", "Received", "Failed");
    choice(d, "kind", "Payment", "Deposit", "Refund", "DepositApplication", "DepositRefund");
    if (old != null && text(old.data(), "status").equals("Received"))
      throw ApiError.invalid(
          "Received financial entries are immutable; create a compensating entry");
    String kind = text(d, "kind");
    if (List.of("Refund", "DepositRefund").contains(kind)) {
      var original = ref(d, "originalPaymentId", "payments");
      String expected = kind.equals("DepositRefund") ? "Deposit" : "Payment";
      if (!text(original.data(), "hireId").equals(text(d, "hireId"))
          || !text(original.data(), "kind").equals(expected)
          || !text(original.data(), "status").equals("Received"))
        throw ApiError.invalid("Refund must reference a received matching entry from this hire");
      long used =
          store.all("payments").stream()
              .filter(
                  p ->
                      !p.id().equals(id)
                          && text(p.data(), "originalPaymentId").equals(original.id().toString())
                          && List.of("Pending", "Received").contains(text(p.data(), "status")))
              .mapToLong(p -> p.data().path("amountMinor").asLong())
              .sum();
      if (used + amount > original.data().path("amountMinor").asLong())
        throw ApiError.invalid("Refund exceeds unrefunded amount");
    }
    if (List.of("DepositApplication", "DepositRefund").contains(kind)) {
      long deposits = 0, used = 0;
      for (var p : store.all("payments"))
        if (!p.id().equals(id)
            && text(p.data(), "hireId").equals(text(d, "hireId"))
            && List.of("Pending", "Received").contains(text(p.data(), "status"))) {
          String k = text(p.data(), "kind");
          if (k.equals("Deposit") && text(p.data(), "status").equals("Received"))
            deposits += p.data().path("amountMinor").asLong();
          if (List.of("DepositApplication", "DepositRefund").contains(k))
            used += p.data().path("amountMinor").asLong();
        }
      if (used + amount > deposits)
        throw ApiError.invalid("Amount exceeds unapplied security deposit");
    }
  }

  void deletion(Row row) {
    if (row.kind().equals("settings")) throw ApiError.invalid("Settings cannot be deleted");
    if (row.kind().equals("payments") && text(row.data(), "status").equals("Received"))
      throw ApiError.invalid("Use a compensating financial entry instead");
    if (row.kind().equals("hires")
        && !List.of("Draft", "Cancelled").contains(text(row.data(), "status")))
      throw ApiError.invalid("Only draft/cancelled hires may be deleted");
    Map<String, String> referenceFields =
        Map.of(
            "vehicles",
            "vehicleId",
            "customers",
            "customerId",
            "hires",
            "hireId",
            "categories",
            "categoryId",
            "providers",
            "providerId",
            "expenses",
            "expenseId",
            "payments",
            "originalPaymentId");
    String field = referenceFields.get(row.kind());
    if (field != null
        && store.snapshot().stream()
            .anyMatch(
                r -> r.deletedAt() == null && text(r.data(), field).equals(row.id().toString())))
      throw ApiError.invalid("Record is referenced; deactivate it instead");
  }
}
