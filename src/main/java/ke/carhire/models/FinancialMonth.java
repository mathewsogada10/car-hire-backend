package ke.carhire.models;

public record FinancialMonth(
    String month, long paymentsReceivedMinor, long expensesPaidMinor, long netProfitMinor) {}
