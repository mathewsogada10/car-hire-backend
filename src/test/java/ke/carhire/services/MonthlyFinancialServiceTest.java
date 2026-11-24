package ke.carhire.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import ke.carhire.exceptions.ApiError;
import ke.carhire.models.*;
import ke.carhire.repositories.RecordRepository;
import org.junit.jupiter.api.Test;

class MonthlyFinancialServiceTest {
  final RecordRepository records = mock(RecordRepository.class);
  final MonthlyFinancialService service = new MonthlyFinancialService(records);
  final UUID vehicle = UUID.randomUUID(), hire = UUID.randomUUID();

  Row row(String kind, String data) throws Exception {
    return new Row(
        kind.equals("hires") ? hire : UUID.randomUUID(),
        kind,
        1,
        new ObjectMapper().readTree(data),
        Instant.EPOCH,
        Instant.EPOCH,
        null);
  }

  @Test
  void financialYearIncludesZeroMonthsRefundsPaidDateAndNegativeCashProfit() throws Exception {
    when(records.snapshot())
        .thenReturn(
            List.of(
                row("hires", "{\"vehicleId\":\"" + vehicle + "\"}"),
                row(
                    "payments",
                    "{\"hireId\":\""
                        + hire
                        + "\",\"status\":\"Received\",\"kind\":\"Payment\",\"amountMinor\":10000,\"date\":\"2025-07-01\"}"),
                row(
                    "payments",
                    "{\"hireId\":\""
                        + hire
                        + "\",\"status\":\"Received\",\"kind\":\"Refund\",\"amountMinor\":2000,\"date\":\"2025-07-31\"}"),
                row(
                    "payments",
                    "{\"hireId\":\""
                        + hire
                        + "\",\"status\":\"Pending\",\"kind\":\"Payment\",\"amountMinor\":99999,\"date\":\"2025-07-01\"}"),
                row(
                    "payments",
                    "{\"hireId\":\""
                        + hire
                        + "\",\"status\":\"Received\",\"kind\":\"Deposit\",\"amountMinor\":99999,\"date\":\"2025-07-01\"}"),
                row(
                    "expenses",
                    "{\"vehicleId\":\""
                        + vehicle
                        + "\",\"status\":\"Paid\",\"amountMinor\":11000,\"date\":\"2025-06-30\",\"paidDate\":\"2025-07-15\"}"),
                row(
                    "expenses",
                    "{\"vehicleId\":\""
                        + vehicle
                        + "\",\"status\":\"Due\",\"amountMinor\":99999,\"date\":\"2025-07-01\"}"),
                row(
                    "expenses",
                    "{\"vehicleId\":\""
                        + vehicle
                        + "\",\"status\":\"Paid\",\"amountMinor\":1000,\"date\":\"2026-06-30\",\"paidDate\":null}")));
    var report = service.calculate(LocalDate.of(2025, 7, 1), LocalDate.of(2026, 6, 30), vehicle);
    assertEquals(12, report.months().size());
    assertEquals(new FinancialMonth("2025-07", 8000, 11000, -3000), report.months().getFirst());
    assertEquals(new FinancialMonth("2025-08", 0, 0, 0), report.months().get(1));
    assertEquals(new FinancialMonth("2026-06", 0, 1000, -1000), report.months().getLast());
    var partial = service.calculate(LocalDate.of(2025, 7, 2), LocalDate.of(2025, 7, 30), vehicle);
    assertEquals(new FinancialMonth("2025-07", 0, 11000, -11000), partial.months().getFirst());
    assertEquals(
        0,
        service
            .calculate(LocalDate.of(2025, 7, 1), LocalDate.of(2026, 6, 30), UUID.randomUUID())
            .months()
            .getFirst()
            .netProfitMinor());
  }

  @Test
  void invalidRangesAreRejectedBeforeReadingRecords() {
    assertThrows(
        ApiError.class,
        () -> service.calculate(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1), null));
    assertThrows(
        ApiError.class,
        () -> service.calculate(LocalDate.of(1900, 1, 1), LocalDate.of(2026, 1, 1), null));
    verifyNoInteractions(records);
  }
}
