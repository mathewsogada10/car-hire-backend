package ke.carhire.controllers;

import java.time.*;
import java.util.*;
import ke.carhire.models.*;
import ke.carhire.services.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {
  private final ReportService reports;
  private final MonthlyFinancialService monthly;

  ReportController(ReportService reports, MonthlyFinancialService monthly) {
    this.reports = reports;
    this.monthly = monthly;
  }

  @GetMapping("/monthly")
  MonthlyFinancialReport monthly(
      @RequestParam LocalDate from,
      @RequestParam LocalDate to,
      @RequestParam(required = false) UUID vehicleId) {
    return monthly.calculate(from, to, vehicleId);
  }

  @GetMapping
  Map<String, Object> report(
      @RequestParam LocalDate from,
      @RequestParam LocalDate to,
      @RequestParam(required = false) UUID vehicleId) {
    return reports.calculate(from, to, vehicleId);
  }

  @GetMapping(value = "/balances.csv", produces = "text/csv")
  String csv(
      @RequestParam LocalDate from,
      @RequestParam LocalDate to,
      @RequestParam(required = false) UUID vehicleId) {
    return reports.balancesCsv(from, to, vehicleId);
  }
}
