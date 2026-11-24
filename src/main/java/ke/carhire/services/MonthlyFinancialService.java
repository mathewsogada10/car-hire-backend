package ke.carhire.services;

import static ke.carhire.services.DomainPolicy.*;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import ke.carhire.exceptions.ApiError;
import ke.carhire.models.*;
import ke.carhire.repositories.RecordRepository;
import org.springframework.stereotype.Service;

@Service
public class MonthlyFinancialService {
  private final RecordRepository records;

  public MonthlyFinancialService(RecordRepository records) {
    this.records = records;
  }

  public MonthlyFinancialReport calculate(LocalDate from, LocalDate to, UUID vehicleId) {
    if (to.isBefore(from)) throw ApiError.invalid("End date precedes start date");
    YearMonth first = YearMonth.from(from), last = YearMonth.from(to);
    if (ChronoUnit.MONTHS.between(first, last) >= 1200)
      throw ApiError.invalid("Select a period covering at most 1200 months (100 years)");
    var rows = records.snapshot();
    Set<String> hires = new HashSet<>();
    for (var row : rows)
      if (row.deletedAt() == null
          && row.kind().equals("hires")
          && (vehicleId == null || text(row.data(), "vehicleId").equals(vehicleId.toString())))
        hires.add(row.id().toString());
    Map<YearMonth, long[]> totals = new LinkedHashMap<>();
    for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1))
      totals.put(month, new long[2]);
    for (var row : rows) {
      if (row.deletedAt() != null) continue;
      var d = row.data();
      long payment = 0, expense = 0;
      LocalDate businessDate;
      if (row.kind().equals("payments")
          && text(d, "status").equals("Received")
          && hires.contains(text(d, "hireId"))) {
        String kind = text(d, "kind");
        if (!kind.equals("Payment") && !kind.equals("Refund")) continue;
        payment = d.path("amountMinor").asLong();
        if (kind.equals("Refund")) payment = Math.negateExact(payment);
        businessDate = date(d, "date");
      } else if (row.kind().equals("expenses")
          && text(d, "status").equals("Paid")
          && (vehicleId == null || text(d, "vehicleId").equals(vehicleId.toString()))) {
        expense = d.path("amountMinor").asLong();
        businessDate = date(d, text(d, "paidDate").isBlank() ? "date" : "paidDate");
      } else continue;
      if (businessDate.isBefore(from) || businessDate.isAfter(to)) continue;
      long[] bucket = totals.get(YearMonth.from(businessDate));
      bucket[0] = Math.addExact(bucket[0], payment);
      bucket[1] = Math.addExact(bucket[1], expense);
    }
    return new MonthlyFinancialReport(
        from,
        to,
        "KES",
        totals.entrySet().stream()
            .map(
                e ->
                    new FinancialMonth(
                        e.getKey().toString(),
                        e.getValue()[0],
                        e.getValue()[1],
                        Math.subtractExact(e.getValue()[0], e.getValue()[1])))
            .toList());
  }
}
