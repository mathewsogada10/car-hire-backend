package ke.carhire.services;

import static ke.carhire.services.DomainPolicy.*;

import java.time.*;
import java.util.*;
import ke.carhire.exceptions.ApiError;
import ke.carhire.models.*;
import ke.carhire.repositories.*;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

@Service
public class ReportService {
  private final RecordRepository store;
  public static final ZoneId ZONE = ZoneId.of("Africa/Nairobi");

  public ReportService(RecordRepository store) {
    this.store = store;
  }

  static boolean within(LocalDate value, LocalDate from, LocalDate to) {
    return !value.isBefore(from) && !value.isAfter(to);
  }

  public Map<String, Object> calculate(LocalDate from, LocalDate to, UUID vehicleId) {
    return calculateRows(store.snapshot(), from, to, vehicleId);
  }

  private static List<Row> kind(List<Row> rows, String kind) {
    return rows.stream().filter(r -> r.deletedAt() == null && r.kind().equals(kind)).toList();
  }

  Map<String, Object> calculateRows(List<Row> rows, LocalDate from, LocalDate to, UUID vehicleId) {
    if (to.isBefore(from)) throw ApiError.invalid("End date precedes start date");
    var hires =
        kind(rows, "hires").stream()
            .filter(
                r -> vehicleId == null || text(r.data(), "vehicleId").equals(vehicleId.toString()))
            .toList();
    Map<UUID, Long> allocations = new HashMap<>(), damage = new HashMap<>();
    Set<UUID> hireIds = new HashSet<>();
    hires.forEach(h -> hireIds.add(h.id()));
    long recognized = 0, collections = 0, deposits = 0, accrued = 0, paid = 0, outstanding = 0;
    for (var p : kind(rows, "payments")) {
      var d = p.data();
      UUID hire = uuid(d, "hireId");
      if (!hireIds.contains(hire) || !text(d, "status").equals("Received")) continue;
      long amount = d.path("amountMinor").asLong();
      String k = text(d, "kind");
      long balanceEffect =
          switch (k) {
            case "Payment", "DepositApplication" -> amount;
            case "Refund" -> -amount;
            default -> 0;
          };
      allocations.merge(hire, balanceEffect, Long::sum);
      if (within(date(d, "date"), from, to)) {
        if (k.equals("Payment")) collections += amount;
        if (k.equals("Refund")) collections -= amount;
        if (k.equals("Deposit")) deposits += amount;
        if (k.equals("DepositRefund")) deposits -= amount;
      }
    }
    for (var r : kind(rows, "damages"))
      if (List.of("Charged", "Settled").contains(text(r.data(), "status"))
          && text(r.data(), "responsibility").equals("Customer"))
        damage.merge(uuid(r.data(), "hireId"), r.data().path("amountMinor").asLong(), Long::sum);
    List<Map<String, Object>> balances = new ArrayList<>();
    for (var h : hires) {
      var d = h.data();
      String status = text(d, "status");
      if (status.equals("Cancelled") || status.equals("Draft")) continue;
      long charge = d.path("chargeMinor").asLong();
      long balance =
          charge + damage.getOrDefault(h.id(), 0L) - allocations.getOrDefault(h.id(), 0L);
      if (status.equals("Completed")
          && within(instant(d, "actualEnd").atZone(ZONE).toLocalDate(), from, to))
        recognized += charge;
      if (within(instant(d, "plannedStart").atZone(ZONE).toLocalDate(), from, to)) {
        outstanding += balance;
        balances.add(
            Map.of(
                "hireId",
                h.id(),
                "vehicleId",
                text(d, "vehicleId"),
                "customerId",
                text(d, "customerId"),
                "chargeMinor",
                charge,
                "allocatedMinor",
                allocations.getOrDefault(h.id(), 0L),
                "outstandingMinor",
                balance));
      }
    }
    for (var e : kind(rows, "expenses")) {
      var d = e.data();
      if (vehicleId != null && !text(d, "vehicleId").equals(vehicleId.toString())
          || text(d, "status").equals("Cancelled")) continue;
      long amount = d.path("amountMinor").asLong();
      if (within(date(d, "date"), from, to)) accrued += amount;
      if (text(d, "status").equals("Paid") && within(date(d, "paidDate"), from, to)) paid += amount;
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("from", from);
    result.put("to", to);
    result.put("currency", "KES");
    result.put("timeZone", "Africa/Nairobi");
    result.put("recognizedMinor", recognized);
    result.put("collectionsMinor", collections);
    result.put("depositCashMinor", deposits);
    result.put("accruedExpensesMinor", accrued);
    result.put("cashExpensesMinor", paid);
    result.put("expectedProfitMinor", recognized - accrued);
    result.put("cashProfitMinor", collections - paid);
    result.put("outstandingMinor", outstanding);
    result.put("balances", balances);
    result.put(
        "dateBasis",
        Map.of(
            "recognized",
            "Completed actual-end date",
            "collections",
            "Payment/refund date; excludes refundable deposits",
            "expenses",
            "Expense date / paid date",
            "balances",
            "Hires with planned pickup in period; current allocations"));
    return result;
  }

  public String balancesCsv(LocalDate from, LocalDate to, UUID vehicleId) {
    var result = calculate(from, to, vehicleId);
    var out = new java.io.StringWriter();
    try (var csv =
        new org.apache.commons.csv.CSVPrinter(
            out,
            org.apache.commons.csv.CSVFormat.RFC4180
                .builder()
                .setHeader(
                    "hire_id",
                    "vehicle_id",
                    "customer_id",
                    "charge_minor",
                    "allocated_minor",
                    "outstanding_minor")
                .get())) {
      for (Object item : (List<?>) result.get("balances")) {
        Map<?, ?> row = (Map<?, ?>) item;
        csv.printRecord(
            row.get("hireId"),
            row.get("vehicleId"),
            row.get("customerId"),
            row.get("chargeMinor"),
            row.get("allocatedMinor"),
            row.get("outstandingMinor"));
      }
    } catch (java.io.IOException e) {
      throw new IllegalStateException("CSV generation failed", e);
    }
    return out.toString();
  }
}
