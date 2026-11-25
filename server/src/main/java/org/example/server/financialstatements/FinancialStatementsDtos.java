package org.example.server.financialstatements;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class FinancialStatementsDtos {
    private FinancialStatementsDtos() {}

    public record StatementLineDto(
            Long accountId,
            String accountCode,
            String accountName,
            String category,
            String subcategory,
            BigDecimal amount
    ) {}

    public record ProfitAndLossDto(
            LocalDate fromDate,
            LocalDate toDate,
            BigDecimal grossRevenue,
            BigDecimal cogs,
            BigDecimal grossProfit,
            BigDecimal operatingExpenses,
            BigDecimal operatingProfit,
            BigDecimal netProfit,
            List<StatementLineDto> revenueLines,
            List<StatementLineDto> cogsLines,
            List<StatementLineDto> expenseLines
    ) {}

    public record BalanceSheetDto(
            LocalDate asOfDate,
            BigDecimal totalAssets,
            BigDecimal totalLiabilities,
            BigDecimal totalEquity,
            List<StatementLineDto> currentAssetLines,
            List<StatementLineDto> nonCurrentAssetLines,
            List<StatementLineDto> currentLiabilityLines,
            List<StatementLineDto> nonCurrentLiabilityLines,
            List<StatementLineDto> equityLines
    ) {}

    public record FinancialAccountRow(
            String accountCode,
            String accountName,
            String accountGroup,
            BigDecimal amount
    ) {}

    public record FinancialRatiosDto(
            BigDecimal grossMarginPct,
            BigDecimal netMarginPct,
            BigDecimal operatingMarginPct,
            BigDecimal currentRatio,
            BigDecimal quickRatio,
            BigDecimal dsoDays,
            BigDecimal dpoDays,
            BigDecimal workingCapital,
            BigDecimal returnOnEquityPct,
            BigDecimal debtToEquityRatio
    ) {
        public FinancialRatiosDto(BigDecimal grossMarginPct, BigDecimal netMarginPct, BigDecimal operatingMarginPct,
                                  BigDecimal currentRatio, BigDecimal quickRatio, BigDecimal dsoDays,
                                  BigDecimal dpoDays, BigDecimal workingCapital) {
            this(grossMarginPct, netMarginPct, operatingMarginPct, currentRatio, quickRatio, dsoDays, dpoDays,
                 workingCapital, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    public record CashFlowStatementDto(
            BigDecimal netProfit,
            BigDecimal openingCash,
            BigDecimal operatingCashFlow,
            BigDecimal investingCashFlow,
            BigDecimal financingCashFlow,
            BigDecimal netCashChange,
            BigDecimal closingCash,
            List<FinancialAccountRow> lines
    ) {}

    public record PriorComparisonDto(
            String priorFromDate,
            String priorToDate,
            BigDecimal priorRevenue,
            BigDecimal priorCogs,
            BigDecimal priorGrossProfit,
            BigDecimal priorNetProfit,
            BigDecimal revenueVariancePct,
            BigDecimal netProfitVariancePct
    ) {}

    public record DesktopProfitAndLossDto(
            String fromDate,
            String toDate,
            BigDecimal totalRevenue,
            BigDecimal costOfGoodsSold,
            BigDecimal grossProfit,
            BigDecimal operatingExpenses,
            BigDecimal netProfit,
            List<FinancialAccountRow> revenueAccounts,
            List<FinancialAccountRow> cogsAccounts,
            List<FinancialAccountRow> expenseAccounts
    ) {}

    public record DesktopBalanceSheetDto(
            String asOfDate,
            BigDecimal totalAssets,
            BigDecimal totalLiabilities,
            BigDecimal totalEquity,
            List<FinancialAccountRow> currentAssets,
            List<FinancialAccountRow> nonCurrentAssets,
            List<FinancialAccountRow> currentLiabilities,
            List<FinancialAccountRow> nonCurrentLiabilities,
            List<FinancialAccountRow> equityAccounts
    ) {}

    public record TradingAccountDto(
            BigDecimal grossSales,
            BigDecimal salesReturns,
            BigDecimal netSales,
            BigDecimal openingStock,
            BigDecimal grossPurchases,
            BigDecimal purchaseReturns,
            BigDecimal netPurchases,
            BigDecimal directExpenses,
            BigDecimal closingStock,
            BigDecimal costOfGoodsSold,
            BigDecimal grossProfit,
            BigDecimal grossMarginPct,
            List<FinancialAccountRow> lines
    ) {}

    public record ThreeTierFinancialStatementDto(
            String fromDate,
            String toDate,
            String engineMode,
            TradingAccountDto trading,
            DesktopProfitAndLossDto pnl,
            DesktopBalanceSheetDto balanceSheet,
            FinancialRatiosDto ratios,
            CashFlowStatementDto cashFlow,
            PriorComparisonDto comparison
    ) {
        public ThreeTierFinancialStatementDto(String fromDate, String toDate, String engineMode,
                                              TradingAccountDto trading, DesktopProfitAndLossDto pnl,
                                              DesktopBalanceSheetDto balanceSheet, FinancialRatiosDto ratios) {
            this(fromDate, toDate, engineMode, trading, pnl, balanceSheet, ratios, null, null);
        }
    }

    public record DrillDownRowDto(
            Long id,
            String referenceNo,
            String date,
            String partyOrAccount,
            String categoryOrDescription,
            BigDecimal taxableAmount,
            BigDecimal taxAmount,
            BigDecimal totalAmount,
            String status
    ) {}

    public record DrillDownResponseDto(
            String category,
            String title,
            BigDecimal totalAmount,
            int recordCount,
            List<DrillDownRowDto> rows
    ) {}
}
