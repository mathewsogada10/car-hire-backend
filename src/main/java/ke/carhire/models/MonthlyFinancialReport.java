package ke.carhire.models;

import java.time.LocalDate;
import java.util.List;

public record MonthlyFinancialReport(
    LocalDate from, LocalDate to, String currency, List<FinancialMonth> months) {}
