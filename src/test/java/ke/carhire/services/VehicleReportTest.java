package ke.carhire.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import ke.carhire.models.Row;
import ke.carhire.repositories.RecordRepository;
import org.junit.jupiter.api.Test;

class VehicleReportTest {
  private final RecordRepository records = mock(RecordRepository.class);
  private final ObjectMapper json = new ObjectMapper();

  private Row row(UUID id, String kind, Map<String, Object> data) {
    return new Row(id, kind, 1, json.valueToTree(data), Instant.EPOCH, Instant.EPOCH, null);
  }

  @Test
  void bothReportsIsolateEachVehicleAndRestoreFleetTotals() {
    UUID first = UUID.randomUUID(), second = UUID.randomUUID();
    List<Row> rows = new ArrayList<>();
    for (int i = 1; i <= 2; i++) {
      String vehicle = (i == 1 ? first : second).toString();
      UUID hire = UUID.randomUUID();
      rows.add(
          row(
              hire,
              "hires",
              Map.of(
                  "vehicleId",
                  vehicle,
                  "customerId",
                  UUID.randomUUID().toString(),
                  "status",
                  "Completed",
                  "plannedStart",
                  "2026-01-01T10:00:00Z",
                  "actualEnd",
                  "2026-01-03T10:00:00Z",
                  "chargeMinor",
                  4000000L * i)));
      for (var entry :
          Map.of("Payment", 1000000L, "Refund", 100000L, "Deposit", 200000L).entrySet())
        rows.add(
            row(
                UUID.randomUUID(),
                "payments",
                Map.of(
                    "hireId",
                    hire.toString(),
                    "status",
                    "Received",
                    "kind",
                    entry.getKey(),
                    "amountMinor",
                    entry.getValue() * i,
                    "date",
                    "2026-01-03")));
      rows.add(
          row(
              UUID.randomUUID(),
              "expenses",
              Map.of(
                  "vehicleId",
                  vehicle,
                  "status",
                  "Paid",
                  "amountMinor",
                  500000L * i,
                  "date",
                  "2026-01-02",
                  "paidDate",
                  "2026-01-03")));
      rows.add(
          row(
              UUID.randomUUID(),
              "damages",
              Map.of(
                  "hireId",
                  hire.toString(),
                  "status",
                  "Charged",
                  "responsibility",
                  "Customer",
                  "amountMinor",
                  200000L * i)));
    }
    when(records.snapshot()).thenReturn(rows);
    var overview = new ReportService(records);
    var monthly = new MonthlyFinancialService(records);
    var from = LocalDate.of(2026, 1, 1);
    var to = LocalDate.of(2026, 1, 31);
    var expected =
        Map.of(
            "recognizedMinor",
            4000000L,
            "collectionsMinor",
            900000L,
            "outstandingMinor",
            3300000L,
            "depositCashMinor",
            200000L,
            "accruedExpensesMinor",
            500000L,
            "cashExpensesMinor",
            500000L,
            "expectedProfitMinor",
            3500000L,
            "cashProfitMinor",
            400000L);
    UUID[] selections = {null, first, second, UUID.randomUUID(), null};
    long[] factors = {3, 1, 2, 0, 3};
    for (int i = 0; i < selections.length; i++) {
      var report = overview.calculate(from, to, selections[i]);
      long factor = factors[i];
      expected.forEach((key, value) -> assertEquals(value * factor, report.get(key), key));
      var month = monthly.calculate(from, to, selections[i]).months().getFirst();
      assertEquals(900000 * factor, month.paymentsReceivedMinor());
      assertEquals(500000 * factor, month.expensesPaidMinor());
      assertEquals(400000 * factor, month.netProfitMinor());
      assertEquals(report.get("cashProfitMinor"), month.netProfitMinor());
    }
  }
}
