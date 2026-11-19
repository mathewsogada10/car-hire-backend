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

  ReportController(ReportService reports) {
    this.reports = reports;
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
