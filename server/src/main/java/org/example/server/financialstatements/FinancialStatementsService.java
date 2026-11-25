package org.example.server.financialstatements;

import org.example.server.insights.CashPositionService;
import org.example.server.persistence.JpaNativeRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class FinancialStatementsService {

    private final JpaNativeRepository jdbc;
    private final ObjectProvider<CashPositionService> cashPositionProvider;

    public FinancialStatementsService(JpaNativeRepository jdbc) {
        this(jdbc, null);
    }

    @Autowired
    public FinancialStatementsService(JpaNativeRepository jdbc, ObjectProvider<CashPositionService> cashPositionProvider) {
        this.jdbc = jdbc;
        this.cashPositionProvider = cashPositionProvider;
    }

    // =========================================================================
    // 1. CANONICAL GENERAL LEDGER MODE
    // =========================================================================

    public FinancialStatementsDtos.ProfitAndLossDto getProfitAndLoss(LocalDate fromDate, LocalDate toDate) {
        LocalDate start = fromDate != null ? fromDate : LocalDate.now().withDayOfMonth(1);
        LocalDate end = toDate != null ? toDate : LocalDate.now();

        List<FinancialStatementsDtos.StatementLineDto> allLines = jdbc.query("""
            SELECT c.id, c.account_code, c.account_name, c.account_type, COALESCE(c.account_subtype, 'GENERAL'),
                   COALESCE(SUM(CASE WHEN e.id IS NOT NULL THEN (l.credit_amount - l.debit_amount) ELSE 0 END), 0) as balance
            FROM chart_of_accounts c
            LEFT JOIN journal_line l ON l.account_id = c.id
            LEFT JOIN journal_entry e ON e.id = l.journal_entry_id
                 AND e.entry_date BETWEEN ? AND ?
                 AND UPPER(COALESCE(e.status, '')) IN ('POSTED', 'REVERSED')
            WHERE c.account_type IN ('REVENUE', 'EXPENSE')
            GROUP BY c.id, c.account_code, c.account_name, c.account_type, c.account_subtype
            ORDER BY c.account_code ASC
            """, (row, idx) -> new FinancialStatementsDtos.StatementLineDto(
                row.getLong(1), row.getString(2), row.getString(3),
                row.getString(4), row.getString(5), row.getBigDecimal(6)
        ), start, end);

        List<FinancialStatementsDtos.StatementLineDto> revenueLines = new ArrayList<>();
        List<FinancialStatementsDtos.StatementLineDto> cogsLines = new ArrayList<>();
        List<FinancialStatementsDtos.StatementLineDto> expenseLines = new ArrayList<>();

        BigDecimal grossRevenue = BigDecimal.ZERO;
        BigDecimal cogs = BigDecimal.ZERO;
        BigDecimal operatingExpenses = BigDecimal.ZERO;

        for (FinancialStatementsDtos.StatementLineDto line : allLines) {
            BigDecimal amt = line.amount();
            if ("REVENUE".equalsIgnoreCase(line.category())) {
                if (amt.compareTo(BigDecimal.ZERO) != 0) {
                    revenueLines.add(line);
                    grossRevenue = grossRevenue.add(amt);
                }
            } else if ("EXPENSE".equalsIgnoreCase(line.category())) {
                BigDecimal expVal = amt.negate();
                if (isDirectCogs(line.subcategory(), line.accountCode(), line.accountName())) {
                    if (expVal.compareTo(BigDecimal.ZERO) != 0) {
                        cogsLines.add(new FinancialStatementsDtos.StatementLineDto(
                                line.accountId(), line.accountCode(), line.accountName(), line.category(), line.subcategory(), expVal
                        ));
                        cogs = cogs.add(expVal);
                    }
                } else {
                    if (expVal.compareTo(BigDecimal.ZERO) != 0) {
                        expenseLines.add(new FinancialStatementsDtos.StatementLineDto(
                                line.accountId(), line.accountCode(), line.accountName(), line.category(), line.subcategory(), expVal
                        ));
                        operatingExpenses = operatingExpenses.add(expVal);
                    }
                }
            }
        }

        BigDecimal grossProfit = grossRevenue.subtract(cogs);
        BigDecimal operatingProfit = grossProfit.subtract(operatingExpenses);
        BigDecimal netProfit = operatingProfit;

        return new FinancialStatementsDtos.ProfitAndLossDto(
                start, end, grossRevenue, cogs, grossProfit, operatingExpenses, operatingProfit, netProfit,
                revenueLines, cogsLines, expenseLines
        );
    }

    public FinancialStatementsDtos.BalanceSheetDto getBalanceSheet(LocalDate asOfDate) {
        LocalDate asOf = asOfDate != null ? asOfDate : LocalDate.now();

        List<FinancialStatementsDtos.StatementLineDto> allLines = jdbc.query("""
            SELECT c.id, c.account_code, c.account_name, c.account_type, COALESCE(c.account_subtype, 'GENERAL'),
                   COALESCE(c.opening_balance, 0) + COALESCE(SUM(
                       CASE
                           WHEN e.id IS NOT NULL THEN (
                               CASE
                                   WHEN c.account_type IN ('ASSET', 'EXPENSE') THEN l.debit_amount - l.credit_amount
                                   ELSE l.credit_amount - l.debit_amount
                               END
                           )
                           ELSE 0
                       END
                   ), 0) as balance
            FROM chart_of_accounts c
            LEFT JOIN journal_line l ON l.account_id = c.id
            LEFT JOIN journal_entry e ON e.id = l.journal_entry_id
                 AND e.entry_date <= ?
                 AND UPPER(COALESCE(e.status, '')) IN ('POSTED', 'REVERSED')
            WHERE c.account_type IN ('ASSET', 'LIABILITY', 'EQUITY')
            GROUP BY c.id, c.account_code, c.account_name, c.account_type, c.account_subtype, c.opening_balance
            ORDER BY c.account_code ASC
            """, (row, idx) -> new FinancialStatementsDtos.StatementLineDto(
                row.getLong(1), row.getString(2), row.getString(3),
                row.getString(4), row.getString(5), row.getBigDecimal(6)
        ), asOf);

        List<FinancialStatementsDtos.StatementLineDto> currentAssetLines = new ArrayList<>();
        List<FinancialStatementsDtos.StatementLineDto> nonCurrentAssetLines = new ArrayList<>();
        List<FinancialStatementsDtos.StatementLineDto> currentLiabilityLines = new ArrayList<>();
        List<FinancialStatementsDtos.StatementLineDto> nonCurrentLiabilityLines = new ArrayList<>();
        List<FinancialStatementsDtos.StatementLineDto> equityLines = new ArrayList<>();

        BigDecimal totalAssets = BigDecimal.ZERO;
        BigDecimal totalLiabilities = BigDecimal.ZERO;
        BigDecimal totalEquity = BigDecimal.ZERO;

        for (FinancialStatementsDtos.StatementLineDto line : allLines) {
            BigDecimal amt = line.amount();
            if (amt.compareTo(BigDecimal.ZERO) == 0) continue;

            if ("ASSET".equalsIgnoreCase(line.category())) {
                totalAssets = totalAssets.add(amt);
                if (isNonCurrentAsset(line.subcategory(), line.accountCode())) {
                    nonCurrentAssetLines.add(line);
                } else {
                    currentAssetLines.add(line);
                }
            } else if ("LIABILITY".equalsIgnoreCase(line.category())) {
                totalLiabilities = totalLiabilities.add(amt);
                if (isNonCurrentLiability(line.subcategory(), line.accountCode())) {
                    nonCurrentLiabilityLines.add(line);
                } else {
                    currentLiabilityLines.add(line);
                }
            } else if ("EQUITY".equalsIgnoreCase(line.category())) {
                totalEquity = totalEquity.add(amt);
                equityLines.add(line);
            }
        }

        BigDecimal currentPeriodEarnings = BigDecimal.ZERO;
        try {
            currentPeriodEarnings = jdbc.queryForObject("""
                SELECT COALESCE(SUM(
                    CASE
                        WHEN c.account_type = 'REVENUE' THEN l.credit_amount - l.debit_amount
                        WHEN c.account_type = 'EXPENSE' THEN -(l.debit_amount - l.credit_amount)
                        ELSE 0
                    END
                ), 0)
                FROM chart_of_accounts c
                JOIN journal_line l ON l.account_id = c.id
                JOIN journal_entry e ON e.id = l.journal_entry_id
                     AND e.entry_date <= ?
                     AND UPPER(COALESCE(e.status, '')) IN ('POSTED', 'REVERSED')
                WHERE c.account_type IN ('REVENUE', 'EXPENSE')
                """, BigDecimal.class, asOf);
        } catch (Exception ignored) {}

        if (currentPeriodEarnings != null && currentPeriodEarnings.compareTo(BigDecimal.ZERO) != 0) {
            equityLines.add(new FinancialStatementsDtos.StatementLineDto(
                    0L, "3990", "Current Period Profit / (Loss)", "EQUITY", "RESERVES", currentPeriodEarnings
            ));
            totalEquity = totalEquity.add(currentPeriodEarnings);
        }

        return new FinancialStatementsDtos.BalanceSheetDto(
                asOf, totalAssets, totalLiabilities, totalEquity,
                currentAssetLines, nonCurrentAssetLines, currentLiabilityLines, nonCurrentLiabilityLines, equityLines
        );
    }

    // =========================================================================
    // 2. DUAL-ENGINE THREE-TIER STATEMENTS (CANONICAL GL & LIVE OPERATIONAL)
    // =========================================================================

    public FinancialStatementsDtos.ThreeTierFinancialStatementDto getThreeTierStatements(LocalDate fromDate, LocalDate toDate, String engine) {
        LocalDate start = fromDate != null ? fromDate : LocalDate.now().withDayOfMonth(1);
        LocalDate end = toDate != null ? toDate : LocalDate.now();
        String startStr = start.toString();
        String endStr = end.toString();
        long daysInPeriod = Math.max(1, ChronoUnit.DAYS.between(start, end) + 1);

        boolean useGl = engine != null && (
                "CANONICAL_GL".equalsIgnoreCase(engine.trim())
                        || "GENERAL_LEDGER".equalsIgnoreCase(engine.trim())
                        || "GL".equalsIgnoreCase(engine.trim())
        );

        if (useGl) {
            return buildCanonicalGlThreeTier(start, end, daysInPeriod);
        }
        return buildOperationalThreeTier(start, end, daysInPeriod);
    }

    private FinancialStatementsDtos.ThreeTierFinancialStatementDto buildCanonicalGlThreeTier(LocalDate start, LocalDate end, long daysInPeriod) {
        String startStr = start.toString();
        String endStr = end.toString();

        FinancialStatementsDtos.ProfitAndLossDto glPnl = getProfitAndLoss(start, end);
        FinancialStatementsDtos.BalanceSheetDto glBs = getBalanceSheet(end);

        BigDecimal salesReturns = glPnl.revenueLines().stream()
                .filter(l -> "4100".equals(l.accountCode()) || "SALES_RETURN".equalsIgnoreCase(l.subcategory()))
                .map(l -> l.amount().negate().max(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grossSales = glPnl.grossRevenue().add(salesReturns);
        BigDecimal netSales = glPnl.grossRevenue();

        BigDecimal purchaseReturns = glPnl.cogsLines().stream()
                .filter(l -> "5100".equals(l.accountCode()))
                .map(l -> l.amount().negate().max(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grossPurchases = glPnl.cogs().add(purchaseReturns);
        BigDecimal directExpenses = glPnl.cogsLines().stream()
                .filter(l -> !"5020".equals(l.accountCode()) && !"5100".equals(l.accountCode()))
                .map(FinancialStatementsDtos.StatementLineDto::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal netPurchases = grossPurchases.subtract(purchaseReturns);
        BigDecimal closingStock = glBs.currentAssetLines().stream()
                .filter(l -> "1060".equals(l.accountCode()) || "1030".equals(l.accountCode()))
                .map(FinancialStatementsDtos.StatementLineDto::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (closingStock.compareTo(BigDecimal.ZERO) == 0) {
            closingStock = loadClosingStockValuation();
        }
        BigDecimal openingStock = loadOpeningStockValuation();

        BigDecimal grossProfit = glPnl.grossProfit();
        BigDecimal grossMarginPct = netSales.compareTo(BigDecimal.ZERO) > 0
                ? grossProfit.multiply(new BigDecimal(100)).divide(netSales, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal netProfit = glPnl.netProfit();
        BigDecimal netMarginPct = netSales.compareTo(BigDecimal.ZERO) > 0
                ? netProfit.multiply(new BigDecimal(100)).divide(netSales, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal opMarginPct = netSales.compareTo(BigDecimal.ZERO) > 0
                ? glPnl.operatingProfit().multiply(new BigDecimal(100)).divide(netSales, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        List<FinancialStatementsDtos.FinancialAccountRow> tradingLines = new ArrayList<>();
        for (var l : glPnl.revenueLines()) {
            tradingLines.add(new FinancialStatementsDtos.FinancialAccountRow(l.accountCode(), l.accountName(), "REVENUE", l.amount()));
        }
        for (var l : glPnl.cogsLines()) {
            tradingLines.add(new FinancialStatementsDtos.FinancialAccountRow(l.accountCode(), l.accountName(), "DIRECT_COST", l.amount()));
        }
        FinancialStatementsDtos.TradingAccountDto tradingDto = new FinancialStatementsDtos.TradingAccountDto(
                grossSales, salesReturns, netSales, openingStock, grossPurchases, purchaseReturns,
                netPurchases, directExpenses, closingStock, glPnl.cogs(), grossProfit, grossMarginPct, tradingLines
        );

        List<FinancialStatementsDtos.FinancialAccountRow> revRows = glPnl.revenueLines().stream()
                .map(l -> new FinancialStatementsDtos.FinancialAccountRow(l.accountCode(), l.accountName(), l.subcategory(), l.amount()))
                .toList();
        List<FinancialStatementsDtos.FinancialAccountRow> cogsRows = glPnl.cogsLines().stream()
                .map(l -> new FinancialStatementsDtos.FinancialAccountRow(l.accountCode(), l.accountName(), l.subcategory(), l.amount()))
                .toList();
        List<FinancialStatementsDtos.FinancialAccountRow> expRows = glPnl.expenseLines().stream()
                .map(l -> new FinancialStatementsDtos.FinancialAccountRow(l.accountCode(), l.accountName(), l.subcategory(), l.amount()))
                .toList();

        FinancialStatementsDtos.DesktopProfitAndLossDto pnlDto = new FinancialStatementsDtos.DesktopProfitAndLossDto(
                startStr, endStr, netSales, glPnl.cogs(), grossProfit, glPnl.operatingExpenses(), netProfit,
                revRows, cogsRows, expRows
        );

        List<FinancialStatementsDtos.FinancialAccountRow> curAssets = glBs.currentAssetLines().stream()
                .map(l -> new FinancialStatementsDtos.FinancialAccountRow(l.accountCode(), l.accountName(), "CURRENT_ASSET", l.amount())).toList();
        List<FinancialStatementsDtos.FinancialAccountRow> nonCurAssets = glBs.nonCurrentAssetLines().stream()
                .map(l -> new FinancialStatementsDtos.FinancialAccountRow(l.accountCode(), l.accountName(), "NON_CURRENT_ASSET", l.amount())).toList();
        List<FinancialStatementsDtos.FinancialAccountRow> curLiab = glBs.currentLiabilityLines().stream()
                .map(l -> new FinancialStatementsDtos.FinancialAccountRow(l.accountCode(), l.accountName(), "CURRENT_LIABILITY", l.amount())).toList();
        List<FinancialStatementsDtos.FinancialAccountRow> nonCurLiab = glBs.nonCurrentLiabilityLines().stream()
                .map(l -> new FinancialStatementsDtos.FinancialAccountRow(l.accountCode(), l.accountName(), "NON_CURRENT_LIABILITY", l.amount())).toList();
        List<FinancialStatementsDtos.FinancialAccountRow> eqRows = glBs.equityLines().stream()
                .map(l -> new FinancialStatementsDtos.FinancialAccountRow(l.accountCode(), l.accountName(), "EQUITY", l.amount())).toList();

        FinancialStatementsDtos.DesktopBalanceSheetDto bsDto = new FinancialStatementsDtos.DesktopBalanceSheetDto(
                endStr, glBs.totalAssets(), glBs.totalLiabilities(), glBs.totalEquity(),
                curAssets, nonCurAssets, curLiab, nonCurLiab, eqRows
        );

        BigDecimal totCurAssets = curAssets.stream().map(FinancialStatementsDtos.FinancialAccountRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totCurLiab = curLiab.stream().map(FinancialStatementsDtos.FinancialAccountRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal debtors = glBs.currentAssetLines().stream()
                .filter(l -> "1040".equals(l.accountCode()) || "1100".equals(l.accountCode()))
                .map(FinancialStatementsDtos.StatementLineDto::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal creditors = glBs.currentLiabilityLines().stream()
                .filter(l -> "2010".equals(l.accountCode()) || "2100".equals(l.accountCode()))
                .map(FinancialStatementsDtos.StatementLineDto::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cashEnd = glBs.currentAssetLines().stream()
                .filter(l -> "1010".equals(l.accountCode()) || "1020".equals(l.accountCode()))
                .map(FinancialStatementsDtos.StatementLineDto::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        FinancialStatementsDtos.FinancialRatiosDto ratiosDto = buildRatios(
                grossMarginPct, netMarginPct, opMarginPct,
                totCurAssets, totCurLiab, closingStock, debtors, creditors, netSales, netPurchases,
                daysInPeriod, netProfit, glBs.totalLiabilities(), glBs.totalEquity()
        );
        FinancialStatementsDtos.CashFlowStatementDto cashFlow = buildCashFlow(start, end, netProfit, cashEnd, glBs);
        FinancialStatementsDtos.PriorComparisonDto comparison = buildPriorComparison(start, end, daysInPeriod, netSales, netProfit, true);

        return new FinancialStatementsDtos.ThreeTierFinancialStatementDto(
                startStr, endStr, "GENERAL_LEDGER", tradingDto, pnlDto, bsDto, ratiosDto, cashFlow, comparison
        );
    }

    private FinancialStatementsDtos.ThreeTierFinancialStatementDto buildOperationalThreeTier(LocalDate start, LocalDate end, long daysInPeriod) {
        String startStr = start.toString();
        String endStr = end.toString();

        BigDecimal grossSales = queryScalar("""
                SELECT COALESCE(SUM(total_amount - COALESCE(gst_amount, 0)), 0)
                FROM sales_header
                WHERE UPPER(COALESCE(document_status, '')) NOT IN ('CANCELLED', 'DELETED', 'REJECTED', 'VOID')
                  AND dse_safe_date(invoice_date) BETWEEN ? AND ?
                """, start, end);
        BigDecimal salesReturns = queryScalar("""
                SELECT COALESCE(SUM(taxable_amount), 0)
                FROM credit_note_header
                WHERE UPPER(COALESCE(status, '')) <> 'CANCELLED'
                  AND note_date BETWEEN ? AND ?
                """, start, end);
        BigDecimal netSales = grossSales.subtract(salesReturns).max(BigDecimal.ZERO);

        BigDecimal grossPurchases = queryScalar("""
                SELECT COALESCE(SUM(total_amount - COALESCE(gst_amount, 0)), 0)
                FROM purchase_header
                WHERE UPPER(COALESCE(document_status, '')) NOT IN ('CANCELLED', 'DELETED', 'REJECTED', 'VOID')
                  AND dse_safe_date(invoice_date) BETWEEN ? AND ?
                """, start, end);
        BigDecimal purchaseReturns = queryScalar("""
                SELECT COALESCE(SUM(taxable_amount), 0)
                FROM debit_note_header
                WHERE UPPER(COALESCE(status, '')) <> 'CANCELLED'
                  AND note_date BETWEEN ? AND ?
                """, start, end);
        BigDecimal netPurchases = grossPurchases.subtract(purchaseReturns).max(BigDecimal.ZERO);

        BigDecimal directExpenses = queryScalar("""
                SELECT COALESCE(SUM(amount), 0)
                FROM finance_register
                WHERE UPPER(COALESCE(voucher_type, '')) = 'EXPENSE'
                  AND UPPER(COALESCE(category, '')) IN ('FREIGHT INWARD', 'CARRIAGE INWARD', 'DIRECT EXPENSE', 'LABOUR', 'MANUFACTURING', 'PACKING')
                  AND dse_safe_date(voucher_date) BETWEEN ? AND ?
                """, start, end);

        BigDecimal closingStock = loadClosingStockValuation();
        BigDecimal openingStock = loadOpeningStockValuation();

        BigDecimal cogs = openingStock.add(netPurchases).add(directExpenses).subtract(closingStock).max(BigDecimal.ZERO);
        BigDecimal grossProfit = netSales.subtract(cogs);
        BigDecimal grossMarginPct = netSales.compareTo(BigDecimal.ZERO) > 0
                ? grossProfit.multiply(new BigDecimal(100)).divide(netSales, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        List<FinancialStatementsDtos.FinancialAccountRow> tradingLines = new ArrayList<>();
        tradingLines.add(new FinancialStatementsDtos.FinancialAccountRow("4010", "Gross Sales Revenue", "SALES", grossSales));
        if (salesReturns.compareTo(BigDecimal.ZERO) > 0) {
            tradingLines.add(new FinancialStatementsDtos.FinancialAccountRow("4100", "Less: Sales Returns (Credit Notes)", "SALES_RETURN", salesReturns.negate()));
        }
        tradingLines.add(new FinancialStatementsDtos.FinancialAccountRow("1060-OP", "Opening Inventory Stock", "OPENING_STOCK", openingStock));
        tradingLines.add(new FinancialStatementsDtos.FinancialAccountRow("5020", "Gross Purchases", "PURCHASES", grossPurchases));
        if (purchaseReturns.compareTo(BigDecimal.ZERO) > 0) {
            tradingLines.add(new FinancialStatementsDtos.FinancialAccountRow("5100", "Less: Purchase Returns (Debit Notes)", "PURCHASE_RETURN", purchaseReturns.negate()));
        }
        if (directExpenses.compareTo(BigDecimal.ZERO) > 0) {
            tradingLines.add(new FinancialStatementsDtos.FinancialAccountRow("5030", "Direct Inward & Manufacturing Expenses", "DIRECT_EXPENSE", directExpenses));
        }
        tradingLines.add(new FinancialStatementsDtos.FinancialAccountRow("1060-CL", "Less: Closing Inventory Stock", "CLOSING_STOCK", closingStock.negate()));

        FinancialStatementsDtos.TradingAccountDto tradingDto = new FinancialStatementsDtos.TradingAccountDto(
                grossSales, salesReturns, netSales, openingStock, grossPurchases, purchaseReturns,
                netPurchases, directExpenses, closingStock, cogs, grossProfit, grossMarginPct, tradingLines
        );

        List<FinancialStatementsDtos.FinancialAccountRow> expenseLines = new ArrayList<>();
        BigDecimal totalOperatingExpenses = BigDecimal.ZERO;
        try {
            List<Map<String, Object>> expRows = jdbc.queryForList("""
                SELECT COALESCE(NULLIF(TRIM(category), ''), 'General Overhead') as cat,
                       COALESCE(SUM(amount), 0) as tot
                FROM finance_register
                WHERE UPPER(COALESCE(voucher_type, '')) = 'EXPENSE'
                  AND UPPER(COALESCE(category, '')) NOT IN ('FREIGHT INWARD', 'CARRIAGE INWARD', 'DIRECT EXPENSE', 'LABOUR', 'MANUFACTURING', 'PACKING')
                  AND dse_safe_date(voucher_date) BETWEEN ? AND ?
                GROUP BY 1
                ORDER BY tot DESC
                """, start, end);
            int expCode = 5080;
            for (Map<String, Object> r : expRows) {
                BigDecimal amt = toBigDecimal(r.get("tot"));
                if (amt.compareTo(BigDecimal.ZERO) > 0) {
                    expenseLines.add(new FinancialStatementsDtos.FinancialAccountRow(
                            String.valueOf(expCode++), String.valueOf(r.get("cat")), "OPERATING_EXPENSE", amt));
                    totalOperatingExpenses = totalOperatingExpenses.add(amt);
                }
            }
        } catch (Exception ignored) {}

        List<FinancialStatementsDtos.FinancialAccountRow> revenueLines = new ArrayList<>();
        revenueLines.add(new FinancialStatementsDtos.FinancialAccountRow("4010", "Net Sales & Operating Revenue", "REVENUE", netSales));
        BigDecimal otherIncome = BigDecimal.ZERO;
        try {
            List<Map<String, Object>> incRows = jdbc.queryForList("""
                SELECT COALESCE(NULLIF(TRIM(category), ''), 'Other Income') as cat,
                       COALESCE(SUM(amount), 0) as tot
                FROM finance_register
                WHERE UPPER(COALESCE(voucher_type, '')) IN ('BANK DEPOSIT', 'INCOME')
                  AND UPPER(COALESCE(category, '')) IN ('OTHER INCOME', 'INTEREST', 'DISCOUNT RECEIVED', 'SCRAP SALE')
                  AND dse_safe_date(voucher_date) BETWEEN ? AND ?
                GROUP BY 1
                ORDER BY tot DESC
                """, start, end);
            int incCode = 4090;
            for (Map<String, Object> r : incRows) {
                BigDecimal amt = toBigDecimal(r.get("tot"));
                if (amt.compareTo(BigDecimal.ZERO) > 0) {
                    revenueLines.add(new FinancialStatementsDtos.FinancialAccountRow(
                            String.valueOf(incCode++), String.valueOf(r.get("cat")), "OTHER_INCOME", amt));
                    otherIncome = otherIncome.add(amt);
                }
            }
        } catch (Exception ignored) {}

        List<FinancialStatementsDtos.FinancialAccountRow> cogsLines = List.of(
                new FinancialStatementsDtos.FinancialAccountRow("5010", "Cost of Goods Sold (COGS)", "COGS", cogs)
        );
        BigDecimal netProfit = grossProfit.subtract(totalOperatingExpenses).add(otherIncome);
        BigDecimal netMarginPct = netSales.compareTo(BigDecimal.ZERO) > 0
                ? netProfit.multiply(new BigDecimal(100)).divide(netSales, 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal opProfit = grossProfit.subtract(totalOperatingExpenses);
        BigDecimal opMarginPct = netSales.compareTo(BigDecimal.ZERO) > 0
                ? opProfit.multiply(new BigDecimal(100)).divide(netSales, 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        FinancialStatementsDtos.DesktopProfitAndLossDto pnlDto = new FinancialStatementsDtos.DesktopProfitAndLossDto(
                startStr, endStr, netSales.add(otherIncome), cogs, grossProfit, totalOperatingExpenses, netProfit,
                revenueLines, cogsLines, expenseLines
        );

        // Authentic Balance Sheet — ZERO fake hardcoded figures
        BigDecimal debtors = queryScalar("""
                SELECT COALESCE(SUM(total_amount - COALESCE(paid_amount, 0)), 0)
                FROM sales_header
                WHERE UPPER(COALESCE(document_status, '')) NOT IN ('CANCELLED', 'DELETED', 'REJECTED', 'VOID')
                  AND (total_amount - COALESCE(paid_amount, 0)) > 0.01
                """);
        BigDecimal creditors = queryScalar("""
                SELECT COALESCE(SUM(total_amount - COALESCE(paid_amount, 0)), 0)
                FROM purchase_header
                WHERE UPPER(COALESCE(document_status, '')) NOT IN ('CANCELLED', 'DELETED', 'REJECTED', 'VOID')
                  AND (total_amount - COALESCE(paid_amount, 0)) > 0.01
                """);

        CashPositionService cashSvc = cashPositionProvider != null ? cashPositionProvider.getIfAvailable() : null;
        BigDecimal cashAndBank = cashSvc != null
                ? BigDecimal.valueOf(cashSvc.cashPosition()).setScale(2, RoundingMode.HALF_UP)
                : queryCoaBalanceSum(end, List.of("1010", "1020"));

        BigDecimal netGstPayable = computeNetGstPosition();

        List<FinancialStatementsDtos.FinancialAccountRow> currentAssets = new ArrayList<>();
        currentAssets.add(new FinancialStatementsDtos.FinancialAccountRow("1020", "Cash & Bank Balances", "CURRENT_ASSET", cashAndBank));
        currentAssets.add(new FinancialStatementsDtos.FinancialAccountRow("1040", "Sundry Debtors (Trade Receivables)", "CURRENT_ASSET", debtors));
        currentAssets.add(new FinancialStatementsDtos.FinancialAccountRow("1060", "Closing Inventory Stock", "CURRENT_ASSET", closingStock));
        if (netGstPayable.compareTo(BigDecimal.ZERO) < 0) {
            currentAssets.add(new FinancialStatementsDtos.FinancialAccountRow("1081", "Input GST Tax Credit (Net ITC)", "CURRENT_ASSET", netGstPayable.abs()));
        }
        BigDecimal totalCurrentAssets = currentAssets.stream()
                .map(FinancialStatementsDtos.FinancialAccountRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        FinancialStatementsDtos.BalanceSheetDto glBs = getBalanceSheet(end);
        List<FinancialStatementsDtos.FinancialAccountRow> nonCurrentAssets = glBs.nonCurrentAssetLines().stream()
                .map(l -> new FinancialStatementsDtos.FinancialAccountRow(l.accountCode(), l.accountName(), "NON_CURRENT_ASSET", l.amount()))
                .toList();
        BigDecimal totalNonCurrentAssets = nonCurrentAssets.stream()
                .map(FinancialStatementsDtos.FinancialAccountRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalAssets = totalCurrentAssets.add(totalNonCurrentAssets);

        List<FinancialStatementsDtos.FinancialAccountRow> currentLiabilities = new ArrayList<>();
        currentLiabilities.add(new FinancialStatementsDtos.FinancialAccountRow("2010", "Sundry Creditors (Trade Payables)", "CURRENT_LIABILITY", creditors));
        if (netGstPayable.compareTo(BigDecimal.ZERO) >= 0) {
            currentLiabilities.add(new FinancialStatementsDtos.FinancialAccountRow("2081", "Duties & Taxes (Net GST Payable)", "CURRENT_LIABILITY", netGstPayable));
        }
        BigDecimal totalCurrentLiabilities = currentLiabilities.stream()
                .map(FinancialStatementsDtos.FinancialAccountRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<FinancialStatementsDtos.FinancialAccountRow> nonCurrentLiabilities = glBs.nonCurrentLiabilityLines().stream()
                .map(l -> new FinancialStatementsDtos.FinancialAccountRow(l.accountCode(), l.accountName(), "NON_CURRENT_LIABILITY", l.amount()))
                .toList();
        BigDecimal totalNonCurrentLiabilities = nonCurrentLiabilities.stream()
                .map(FinancialStatementsDtos.FinancialAccountRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalLiab = totalCurrentLiabilities.add(totalNonCurrentLiabilities);

        List<FinancialStatementsDtos.FinancialAccountRow> equityAccounts = new ArrayList<>();
        BigDecimal glEqSum = BigDecimal.ZERO;
        for (var l : glBs.equityLines()) {
            if ("3990".equals(l.accountCode())) continue;
            equityAccounts.add(new FinancialStatementsDtos.FinancialAccountRow(l.accountCode(), l.accountName(), "EQUITY", l.amount()));
            glEqSum = glEqSum.add(l.amount());
        }
        equityAccounts.add(new FinancialStatementsDtos.FinancialAccountRow("3020", "Current Period Net Profit / (Loss)", "EQUITY", netProfit));
        BigDecimal totalEquity = glEqSum.add(netProfit);

        FinancialStatementsDtos.DesktopBalanceSheetDto bsDto = new FinancialStatementsDtos.DesktopBalanceSheetDto(
                endStr, totalAssets, totalLiab, totalEquity,
                currentAssets, nonCurrentAssets, currentLiabilities, nonCurrentLiabilities, equityAccounts
        );

        FinancialStatementsDtos.FinancialRatiosDto ratiosDto = buildRatios(
                grossMarginPct, netMarginPct, opMarginPct,
                totalCurrentAssets, totalCurrentLiabilities, closingStock, debtors, creditors,
                netSales, netPurchases, daysInPeriod, netProfit, totalLiab, totalEquity
        );
        FinancialStatementsDtos.CashFlowStatementDto cashFlow = buildCashFlow(start, end, netProfit, cashAndBank, glBs);
        FinancialStatementsDtos.PriorComparisonDto comparison = buildPriorComparison(start, end, daysInPeriod, netSales, netProfit, false);

        return new FinancialStatementsDtos.ThreeTierFinancialStatementDto(
                startStr, endStr, "OPERATIONAL",
                tradingDto, pnlDto, bsDto, ratiosDto, cashFlow, comparison
        );
    }

    private FinancialStatementsDtos.FinancialRatiosDto buildRatios(
            BigDecimal grossMarginPct, BigDecimal netMarginPct, BigDecimal opMarginPct,
            BigDecimal totalCurrentAssets, BigDecimal totalCurrentLiabilities, BigDecimal closingStock,
            BigDecimal debtors, BigDecimal creditors, BigDecimal netSales, BigDecimal netPurchases,
            long daysInPeriod, BigDecimal netProfit, BigDecimal totalLiab, BigDecimal totalEquity) {
        BigDecimal curRatio = totalCurrentLiabilities.compareTo(BigDecimal.ZERO) > 0
                ? totalCurrentAssets.divide(totalCurrentLiabilities, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal qkRatio = totalCurrentLiabilities.compareTo(BigDecimal.ZERO) > 0
                ? totalCurrentAssets.subtract(closingStock).max(BigDecimal.ZERO).divide(totalCurrentLiabilities, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal dso = netSales.compareTo(BigDecimal.ZERO) > 0
                ? debtors.multiply(BigDecimal.valueOf(daysInPeriod)).divide(netSales, 1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal dpo = netPurchases.compareTo(BigDecimal.ZERO) > 0
                ? creditors.multiply(BigDecimal.valueOf(daysInPeriod)).divide(netPurchases, 1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal workingCapital = totalCurrentAssets.subtract(totalCurrentLiabilities);
        BigDecimal roePct = totalEquity.compareTo(BigDecimal.ZERO) > 0
                ? netProfit.multiply(new BigDecimal(100)).divide(totalEquity, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal debtToEquity = totalEquity.compareTo(BigDecimal.ZERO) > 0
                ? totalLiab.divide(totalEquity, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        return new FinancialStatementsDtos.FinancialRatiosDto(
                grossMarginPct, netMarginPct, opMarginPct, curRatio, qkRatio, dso, dpo,
                workingCapital, roePct, debtToEquity
        );
    }

    private FinancialStatementsDtos.CashFlowStatementDto buildCashFlow(
            LocalDate start, LocalDate end, BigDecimal netProfit, BigDecimal closingCash,
            FinancialStatementsDtos.BalanceSheetDto endBs) {
        FinancialStatementsDtos.BalanceSheetDto startBs = getBalanceSheet(start.minusDays(1));
        BigDecimal cashStart = startBs.currentAssetLines().stream()
                .filter(l -> "1010".equals(l.accountCode()) || "1020".equals(l.accountCode()))
                .map(FinancialStatementsDtos.StatementLineDto::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal endAr = sumLinesByCode(endBs.currentAssetLines(), Set.of("1040", "1100"));
        BigDecimal startAr = sumLinesByCode(startBs.currentAssetLines(), Set.of("1040", "1100"));
        BigDecimal deltaAr = endAr.subtract(startAr); // increase in AR uses cash

        BigDecimal endInv = sumLinesByCode(endBs.currentAssetLines(), Set.of("1060", "1030"));
        BigDecimal startInv = sumLinesByCode(startBs.currentAssetLines(), Set.of("1060", "1030"));
        BigDecimal deltaInv = endInv.subtract(startInv); // increase in inventory uses cash

        BigDecimal endAp = sumLinesByCode(endBs.currentLiabilityLines(), Set.of("2010", "2100"));
        BigDecimal startAp = sumLinesByCode(startBs.currentLiabilityLines(), Set.of("2010", "2100"));
        BigDecimal deltaAp = endAp.subtract(startAp); // increase in AP provides cash

        BigDecimal operatingCashFlow = netProfit.subtract(deltaAr).subtract(deltaInv).add(deltaAp);

        BigDecimal endFixed = endBs.nonCurrentAssetLines().stream()
                .map(FinancialStatementsDtos.StatementLineDto::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal startFixed = startBs.nonCurrentAssetLines().stream()
                .map(FinancialStatementsDtos.StatementLineDto::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal investingCashFlow = startFixed.subtract(endFixed); // asset purchases are negative

        BigDecimal endLoans = endBs.nonCurrentLiabilityLines().stream()
                .map(FinancialStatementsDtos.StatementLineDto::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal startLoans = startBs.nonCurrentLiabilityLines().stream()
                .map(FinancialStatementsDtos.StatementLineDto::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal endCap = sumLinesByCode(endBs.equityLines(), Set.of("3010", "3030"));
        BigDecimal startCap = sumLinesByCode(startBs.equityLines(), Set.of("3010", "3030"));
        BigDecimal financingCashFlow = endLoans.subtract(startLoans).add(endCap.subtract(startCap));

        BigDecimal netCashChange = operatingCashFlow.add(investingCashFlow).add(financingCashFlow);
        List<FinancialStatementsDtos.FinancialAccountRow> cfLines = List.of(
                new FinancialStatementsDtos.FinancialAccountRow("CF-NP", "Net Profit Before Working Capital Adjustments", "OPERATING", netProfit),
                new FinancialStatementsDtos.FinancialAccountRow("CF-AR", "(Increase) / Decrease in Trade Receivables", "OPERATING", deltaAr.negate()),
                new FinancialStatementsDtos.FinancialAccountRow("CF-INV", "(Increase) / Decrease in Inventory", "OPERATING", deltaInv.negate()),
                new FinancialStatementsDtos.FinancialAccountRow("CF-AP", "Increase / (Decrease) in Trade Payables", "OPERATING", deltaAp),
                new FinancialStatementsDtos.FinancialAccountRow("CF-OP", "Net Cash Flow from Operating Activities", "OPERATING_TOTAL", operatingCashFlow),
                new FinancialStatementsDtos.FinancialAccountRow("CF-INVST", "Net Cash Flow from Investing Activities (Fixed Assets)", "INVESTING", investingCashFlow),
                new FinancialStatementsDtos.FinancialAccountRow("CF-FIN", "Net Cash Flow from Financing Activities (Borrowings & Capital)", "FINANCING", financingCashFlow)
        );
        return new FinancialStatementsDtos.CashFlowStatementDto(
                netProfit, cashStart, operatingCashFlow, investingCashFlow, financingCashFlow, netCashChange, closingCash, cfLines
        );
    }

    private FinancialStatementsDtos.PriorComparisonDto buildPriorComparison(
            LocalDate start, LocalDate end, long daysInPeriod, BigDecimal curSales, BigDecimal curProfit, boolean useGl) {
        LocalDate priorEnd = start.minusDays(1);
        LocalDate priorStart = priorEnd.minusDays(daysInPeriod - 1);
        BigDecimal prevSales;
        BigDecimal prevCogs;
        BigDecimal prevGross;
        BigDecimal prevNet;
        if (useGl) {
            FinancialStatementsDtos.ProfitAndLossDto p = getProfitAndLoss(priorStart, priorEnd);
            prevSales = p.grossRevenue();
            prevCogs = p.cogs();
            prevGross = p.grossProfit();
            prevNet = p.netProfit();
        } else {
            BigDecimal gSales = queryScalar("SELECT COALESCE(SUM(total_amount - COALESCE(gst_amount, 0)), 0) FROM sales_header WHERE UPPER(COALESCE(document_status,'')) NOT IN ('CANCELLED','DELETED','REJECTED','VOID') AND dse_safe_date(invoice_date) BETWEEN ? AND ?", priorStart, priorEnd);
            BigDecimal sRet = queryScalar("SELECT COALESCE(SUM(taxable_amount), 0) FROM credit_note_header WHERE UPPER(COALESCE(status,'')) <> 'CANCELLED' AND note_date BETWEEN ? AND ?", priorStart, priorEnd);
            prevSales = gSales.subtract(sRet).max(BigDecimal.ZERO);
            BigDecimal gPur = queryScalar("SELECT COALESCE(SUM(total_amount - COALESCE(gst_amount, 0)), 0) FROM purchase_header WHERE UPPER(COALESCE(document_status,'')) NOT IN ('CANCELLED','DELETED','REJECTED','VOID') AND dse_safe_date(invoice_date) BETWEEN ? AND ?", priorStart, priorEnd);
            BigDecimal pRet = queryScalar("SELECT COALESCE(SUM(taxable_amount), 0) FROM debit_note_header WHERE UPPER(COALESCE(status,'')) <> 'CANCELLED' AND note_date BETWEEN ? AND ?", priorStart, priorEnd);
            prevCogs = gPur.subtract(pRet).max(BigDecimal.ZERO);
            prevGross = prevSales.subtract(prevCogs);
            BigDecimal exp = queryScalar("SELECT COALESCE(SUM(amount), 0) FROM finance_register WHERE UPPER(COALESCE(voucher_type,'')) = 'EXPENSE' AND dse_safe_date(voucher_date) BETWEEN ? AND ?", priorStart, priorEnd);
            prevNet = prevGross.subtract(exp);
        }
        BigDecimal revVar = prevSales.compareTo(BigDecimal.ZERO) > 0
                ? curSales.subtract(prevSales).multiply(new BigDecimal(100)).divide(prevSales, 1, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal profVar = prevNet.abs().compareTo(BigDecimal.ZERO) > 0
                ? curProfit.subtract(prevNet).multiply(new BigDecimal(100)).divide(prevNet.abs(), 1, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        return new FinancialStatementsDtos.PriorComparisonDto(
                priorStart.toString(), priorEnd.toString(), prevSales, prevCogs, prevGross, prevNet, revVar, profVar
        );
    }

    public FinancialStatementsDtos.DesktopProfitAndLossDto getDesktopProfitAndLoss(LocalDate fromDate, LocalDate toDate) {
        return getThreeTierStatements(fromDate, toDate, "OPERATIONAL").pnl();
    }

    public FinancialStatementsDtos.DesktopBalanceSheetDto getDesktopBalanceSheet(LocalDate asOfDate) {
        LocalDate start = asOfDate != null ? asOfDate.withDayOfMonth(1) : LocalDate.now().withDayOfMonth(1);
        return getThreeTierStatements(start, asOfDate, "OPERATIONAL").balanceSheet();
    }

    // =========================================================================
    // 3. INTERACTIVE TRANSACTION DRILL-DOWN ENGINE
    // =========================================================================

    public FinancialStatementsDtos.DrillDownResponseDto getDrillDown(String category, LocalDate fromDate, LocalDate toDate) {
        LocalDate start = fromDate != null ? fromDate : LocalDate.now().withDayOfMonth(1);
        LocalDate end = toDate != null ? toDate : LocalDate.now();
        String endStr = end.toString();
        String cat = category != null ? category.trim().toUpperCase(Locale.ROOT) : "SALES";

        List<FinancialStatementsDtos.DrillDownRowDto> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        String title;

        switch (cat) {
            case "SALES", "REVENUE" -> {
                title = "Sales Register Invoices Drill-Down (" + start + " to " + end + ")";
                List<Map<String, Object>> sList = jdbc.queryForList("""
                    SELECT s.id, s.invoice_no, s.invoice_date,
                           COALESCE(NULLIF(s.customer_name_snapshot, ''), p.name, 'Customer') as party,
                           s.total_amount, s.gst_amount, s.subtotal, s.payment_status
                    FROM sales_header s
                    LEFT JOIN party_master p ON p.id = s.customer_id
                    WHERE UPPER(COALESCE(s.document_status, '')) NOT IN ('CANCELLED', 'DELETED', 'REJECTED', 'VOID')
                      AND dse_safe_date(s.invoice_date) BETWEEN ? AND ?
                    ORDER BY dse_safe_date(s.invoice_date) DESC, s.id DESC
                    """, start, end);
                for (Map<String, Object> s : sList) {
                    BigDecimal tot = toBigDecimal(s.get("total_amount"));
                    BigDecimal gst = toBigDecimal(s.get("gst_amount"));
                    BigDecimal sub = toBigDecimal(s.get("subtotal"));
                    BigDecimal tx = sub.compareTo(BigDecimal.ZERO) > 0 ? sub : tot.subtract(gst);
                    total = total.add(tx);
                    rows.add(new FinancialStatementsDtos.DrillDownRowDto(
                            ((Number) s.get("id")).longValue(), String.valueOf(s.get("invoice_no")),
                            String.valueOf(s.get("invoice_date")), String.valueOf(s.get("party")),
                            "Tax Invoice", tx, gst, tot, String.valueOf(s.get("payment_status"))
                    ));
                }
            }
            case "PURCHASES", "PURCHASE", "COGS", "DIRECT" -> {
                title = "Purchase Register Bills Drill-Down (" + start + " to " + end + ")";
                List<Map<String, Object>> pList = jdbc.queryForList("""
                    SELECT p.id, p.invoice_no, p.invoice_date,
                           COALESCE(NULLIF(p.supplier_name_snapshot, ''), pm.name, 'Supplier') as party,
                           p.total_amount, p.gst_amount, p.subtotal, p.payment_status
                    FROM purchase_header p
                    LEFT JOIN party_master pm ON pm.id = p.supplier_id
                    WHERE UPPER(COALESCE(p.document_status, '')) NOT IN ('CANCELLED', 'DELETED', 'REJECTED', 'VOID')
                      AND dse_safe_date(p.invoice_date) BETWEEN ? AND ?
                    ORDER BY dse_safe_date(p.invoice_date) DESC, p.id DESC
                    """, start, end);
                for (Map<String, Object> p : pList) {
                    BigDecimal tot = toBigDecimal(p.get("total_amount"));
                    BigDecimal gst = toBigDecimal(p.get("gst_amount"));
                    BigDecimal sub = toBigDecimal(p.get("subtotal"));
                    BigDecimal tx = sub.compareTo(BigDecimal.ZERO) > 0 ? sub : tot.subtract(gst);
                    total = total.add(tx);
                    rows.add(new FinancialStatementsDtos.DrillDownRowDto(
                            ((Number) p.get("id")).longValue(), String.valueOf(p.get("invoice_no")),
                            String.valueOf(p.get("invoice_date")), String.valueOf(p.get("party")),
                            "Purchase Bill", tx, gst, tot, String.valueOf(p.get("payment_status"))
                    ));
                }
            }
            case "CLOSING_STOCK", "INVENTORY", "OPENING_STOCK" -> {
                title = "Inventory Valuation Breakdown";
                List<Map<String, Object>> iList = jdbc.queryForList("""
                    SELECT i.id, i.item_code, i.description,
                           COALESCE(s.quantity, i.opening_stock, 0) as qty,
                           COALESCE(s.average_unit_cost, i.purchase_price, 0) as cost
                    FROM item_master i
                    LEFT JOIN inventory_cost_state s ON s.item_code = i.item_code
                    WHERE COALESCE(i.is_active::text, '1') IN ('1', 'true', 't')
                      AND COALESCE(s.quantity, i.opening_stock, 0) > 0
                    ORDER BY 4 DESC
                    """);
                for (Map<String, Object> i : iList) {
                    BigDecimal qty = toBigDecimal(i.get("qty"));
                    BigDecimal cost = toBigDecimal(i.get("cost"));
                    BigDecimal val = qty.multiply(cost).setScale(2, RoundingMode.HALF_UP);
                    total = total.add(val);
                    rows.add(new FinancialStatementsDtos.DrillDownRowDto(
                            ((Number) i.get("id")).longValue(), String.valueOf(i.get("item_code")),
                            endStr, String.valueOf(i.get("description")),
                            "Stock Qty: " + qty + " @ ₹" + cost, val, BigDecimal.ZERO, val, "IN STOCK"
                    ));
                }
            }
            case "DEBTORS", "RECEIVABLES" -> {
                title = "Sundry Debtors (Unpaid Invoices)";
                List<Map<String, Object>> dList = jdbc.queryForList("""
                    SELECT s.id, s.invoice_no, s.invoice_date,
                           COALESCE(NULLIF(s.customer_name_snapshot, ''), p.name, 'Customer') as party,
                           s.total_amount, s.paid_amount, (s.total_amount - COALESCE(s.paid_amount, 0)) as bal
                    FROM sales_header s
                    LEFT JOIN party_master p ON p.id = s.customer_id
                    WHERE UPPER(COALESCE(s.document_status, '')) NOT IN ('CANCELLED', 'DELETED', 'REJECTED', 'VOID')
                      AND (s.total_amount - COALESCE(s.paid_amount, 0)) > 0.01
                    ORDER BY 7 DESC
                    """);
                for (Map<String, Object> d : dList) {
                    BigDecimal tot = toBigDecimal(d.get("total_amount"));
                    BigDecimal bal = toBigDecimal(d.get("bal"));
                    total = total.add(bal);
                    rows.add(new FinancialStatementsDtos.DrillDownRowDto(
                            ((Number) d.get("id")).longValue(), String.valueOf(d.get("invoice_no")),
                            String.valueOf(d.get("invoice_date")), String.valueOf(d.get("party")),
                            "Total: ₹" + tot + " | Outstanding", bal, BigDecimal.ZERO, bal, "UNPAID"
                    ));
                }
            }
            case "CREDITORS", "PAYABLES" -> {
                title = "Sundry Creditors (Unpaid Bills)";
                List<Map<String, Object>> cList = jdbc.queryForList("""
                    SELECT p.id, p.invoice_no, p.invoice_date,
                           COALESCE(NULLIF(p.supplier_name_snapshot, ''), pm.name, 'Supplier') as party,
                           p.total_amount, p.paid_amount, (p.total_amount - COALESCE(p.paid_amount, 0)) as bal
                    FROM purchase_header p
                    LEFT JOIN party_master pm ON pm.id = p.supplier_id
                    WHERE UPPER(COALESCE(p.document_status, '')) NOT IN ('CANCELLED', 'DELETED', 'REJECTED', 'VOID')
                      AND (p.total_amount - COALESCE(p.paid_amount, 0)) > 0.01
                    ORDER BY 7 DESC
                    """);
                for (Map<String, Object> c : cList) {
                    BigDecimal tot = toBigDecimal(c.get("total_amount"));
                    BigDecimal bal = toBigDecimal(c.get("bal"));
                    total = total.add(bal);
                    rows.add(new FinancialStatementsDtos.DrillDownRowDto(
                            ((Number) c.get("id")).longValue(), String.valueOf(c.get("invoice_no")),
                            String.valueOf(c.get("invoice_date")), String.valueOf(c.get("party")),
                            "Total: ₹" + tot + " | Outstanding", bal, BigDecimal.ZERO, bal, "UNPAID"
                    ));
                }
            }
            case "CASH_BANK" -> {
                title = "Cash & Bank Movements (" + start + " to " + end + ")";
                List<Map<String, Object>> fList = jdbc.queryForList("""
                    SELECT f.id, COALESCE(f.voucher_no, CAST(f.id AS VARCHAR)) as ref_no, f.voucher_date,
                           COALESCE(f.account_name, f.category, 'Cash/Bank') as party,
                           COALESCE(f.voucher_type, '') as vtype, COALESCE(f.notes, '') as notes, f.amount
                    FROM finance_register f
                    WHERE dse_safe_date(f.voucher_date) BETWEEN ? AND ?
                    ORDER BY dse_safe_date(f.voucher_date) DESC, f.id DESC
                    """, start, end);
                for (Map<String, Object> f : fList) {
                    BigDecimal amt = toBigDecimal(f.get("amount"));
                    String vt = String.valueOf(f.get("vtype"));
                    BigDecimal signed = vt.toUpperCase(Locale.ROOT).contains("DEPOSIT") ? amt : amt.negate();
                    total = total.add(signed);
                    rows.add(new FinancialStatementsDtos.DrillDownRowDto(
                            ((Number) f.get("id")).longValue(), String.valueOf(f.get("ref_no")),
                            String.valueOf(f.get("voucher_date")), String.valueOf(f.get("party")),
                            vt + " " + String.valueOf(f.get("notes")), amt, BigDecimal.ZERO, signed, vt
                    ));
                }
                List<Map<String, Object>> payList = jdbc.queryForList("""
                    SELECT p.id, COALESCE(p.payment_reference, p.reference_no, CAST(p.id AS VARCHAR)) as ref_no,
                           p.payment_date::TEXT as dt, COALESCE(p.received_from, p.payment_mode, 'Payment') as party,
                           p.document_type, p.payment_mode, p.amount
                    FROM payment_record p
                    WHERE dse_safe_date(p.payment_date::TEXT) BETWEEN ? AND ?
                    ORDER BY p.id DESC LIMIT 200
                    """, start, end);
                for (Map<String, Object> p : payList) {
                    BigDecimal amt = toBigDecimal(p.get("amount"));
                    boolean inflow = "SALE".equalsIgnoreCase(String.valueOf(p.get("document_type")));
                    BigDecimal signed = inflow ? amt : amt.negate();
                    total = total.add(signed);
                    rows.add(new FinancialStatementsDtos.DrillDownRowDto(
                            ((Number) p.get("id")).longValue(), String.valueOf(p.get("ref_no")),
                            String.valueOf(p.get("dt")), String.valueOf(p.get("party")),
                            (inflow ? "Receipt via " : "Payout via ") + p.get("payment_mode"),
                            amt, BigDecimal.ZERO, signed, inflow ? "RECEIPT" : "PAYMENT"
                    ));
                }
            }
            case "GST", "DUTIES_TAXES" -> {
                title = "GST Tax Ledgers Drill-Down (Up to " + end + ")";
                populateGlCodeDrillDown(rows, Set.of("1081", "1082", "1083", "2081", "2082", "2083", "2085"), start, end);
                total = rows.stream().map(FinancialStatementsDtos.DrillDownRowDto::totalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            }
            case "FIXED_ASSETS", "BORROWINGS", "CAPITAL", "EQUITY" -> {
                title = cat.replace('_', ' ') + " Ledger Movements (" + start + " to " + end + ")";
                Set<String> codes = switch (cat) {
                    case "FIXED_ASSETS" -> Set.of("1510");
                    case "BORROWINGS" -> Set.of("2510");
                    default -> Set.of("3010", "3030", "3999");
                };
                populateGlCodeDrillDown(rows, codes, LocalDate.of(2000, 1, 1), end);
                total = rows.stream().map(FinancialStatementsDtos.DrillDownRowDto::totalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            }
            default -> {
                title = "Expense Vouchers (" + category + ") (" + start + " to " + end + ")";
                List<Map<String, Object>> eList = jdbc.queryForList("""
                    SELECT f.id, f.voucher_no, f.voucher_date,
                           COALESCE(f.account_name, p.name, f.category, 'Expense') as payee,
                           f.category, f.amount, f.payment_mode
                    FROM finance_register f
                    LEFT JOIN party_master p ON p.id = f.party_id
                    WHERE UPPER(COALESCE(f.voucher_type, '')) = 'EXPENSE'
                      AND (? = 'ALL' OR UPPER(COALESCE(f.category, '')) = ?)
                      AND dse_safe_date(f.voucher_date) BETWEEN ? AND ?
                    ORDER BY dse_safe_date(f.voucher_date) DESC, f.id DESC
                    """, cat.equals("EXPENSES") ? "ALL" : cat, cat, start, end);
                for (Map<String, Object> e : eList) {
                    BigDecimal amt = toBigDecimal(e.get("amount"));
                    total = total.add(amt);
                    rows.add(new FinancialStatementsDtos.DrillDownRowDto(
                            ((Number) e.get("id")).longValue(), String.valueOf(e.get("voucher_no")),
                            String.valueOf(e.get("voucher_date")), String.valueOf(e.get("payee")),
                            String.valueOf(e.get("category")), amt, BigDecimal.ZERO, amt, String.valueOf(e.get("payment_mode"))
                    ));
                }
            }
        }

        return new FinancialStatementsDtos.DrillDownResponseDto(cat, title, total, rows.size(), rows);
    }

    private void populateGlCodeDrillDown(List<FinancialStatementsDtos.DrillDownRowDto> rows, Set<String> codes, LocalDate from, LocalDate to) {
        if (codes.isEmpty()) return;
        String placeholders = String.join(",", Collections.nCopies(codes.size(), "?"));
        List<Object> args = new ArrayList<>(codes);
        args.add(from);
        args.add(to);
        List<Map<String, Object>> list = jdbc.queryForList("""
            SELECT je.id, je.entry_number, je.entry_date::TEXT as dt,
                   ca.account_code || ' - ' || ca.account_name as acc,
                   COALESCE(jl.line_narration, je.narration, '') as descr,
                   jl.debit_amount, jl.credit_amount, je.status
            FROM journal_line jl
            JOIN journal_entry je ON je.id = jl.journal_entry_id
            JOIN chart_of_accounts ca ON ca.id = jl.account_id
            WHERE ca.account_code IN (""" + placeholders + """
            )
              AND je.entry_date BETWEEN ? AND ?
              AND UPPER(COALESCE(je.status, '')) IN ('POSTED', 'REVERSED')
            ORDER BY je.entry_date DESC, je.id DESC
            """, args.toArray());
        for (Map<String, Object> m : list) {
            BigDecimal dr = toBigDecimal(m.get("debit_amount"));
            BigDecimal cr = toBigDecimal(m.get("credit_amount"));
            rows.add(new FinancialStatementsDtos.DrillDownRowDto(
                    ((Number) m.get("id")).longValue(), String.valueOf(m.get("entry_number")),
                    String.valueOf(m.get("dt")), String.valueOf(m.get("acc")),
                    String.valueOf(m.get("descr")), dr, cr, cr.subtract(dr), String.valueOf(m.get("status"))
            ));
        }
    }

    private BigDecimal computeNetGstPosition() {
        BigDecimal outSales = queryScalar("SELECT COALESCE(SUM(COALESCE(gst_amount, 0)), 0) FROM sales_header WHERE UPPER(COALESCE(document_status,'')) NOT IN ('CANCELLED','DELETED','REJECTED','VOID')");
        BigDecimal outCn = queryScalar("SELECT COALESCE(SUM(COALESCE(cgst_amount,0)+COALESCE(sgst_amount,0)+COALESCE(igst_amount,0)), 0) FROM credit_note_header WHERE UPPER(COALESCE(status,'')) <> 'CANCELLED'");
        BigDecimal inPur = queryScalar("SELECT COALESCE(SUM(COALESCE(gst_amount, 0)), 0) FROM purchase_header WHERE UPPER(COALESCE(document_status,'')) NOT IN ('CANCELLED','DELETED','REJECTED','VOID')");
        BigDecimal inDn = queryScalar("SELECT COALESCE(SUM(COALESCE(cgst_amount,0)+COALESCE(sgst_amount,0)+COALESCE(igst_amount,0)), 0) FROM debit_note_header WHERE UPPER(COALESCE(status,'')) <> 'CANCELLED'");
        return outSales.subtract(outCn).subtract(inPur.subtract(inDn)).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal loadClosingStockValuation() {
        return queryScalar("""
                SELECT COALESCE(SUM(COALESCE(s.quantity, i.opening_stock, 0) * COALESCE(s.average_unit_cost, i.purchase_price, 0)), 0)
                FROM item_master i
                LEFT JOIN inventory_cost_state s ON s.item_code = i.item_code
                WHERE COALESCE(i.is_active::text, '1') IN ('1', 'true', 't')
                  AND COALESCE(s.quantity, i.opening_stock, 0) > 0
                """);
    }

    private BigDecimal loadOpeningStockValuation() {
        return queryScalar("""
                SELECT COALESCE(SUM(COALESCE(i.opening_stock, 0) * COALESCE(i.purchase_price, 0)), 0)
                FROM item_master i
                WHERE COALESCE(i.is_active::text, '1') IN ('1', 'true', 't')
                  AND COALESCE(i.opening_stock, 0) > 0
                """);
    }

    private BigDecimal queryCoaBalanceSum(LocalDate asOf, List<String> codes) {
        if (codes == null || codes.isEmpty()) return BigDecimal.ZERO;
        String placeholders = String.join(",", Collections.nCopies(codes.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(asOf);
        args.addAll(codes);
        return queryScalar("""
            SELECT COALESCE(SUM(c.opening_balance + COALESCE(sub.mv, 0)), 0)
            FROM chart_of_accounts c
            LEFT JOIN (
                SELECT l.account_id, SUM(CASE WHEN ca.account_type IN ('ASSET','EXPENSE') THEN l.debit_amount - l.credit_amount ELSE l.credit_amount - l.debit_amount END) as mv
                FROM journal_line l
                JOIN journal_entry e ON e.id = l.journal_entry_id
                JOIN chart_of_accounts ca ON ca.id = l.account_id
                WHERE e.entry_date <= ? AND UPPER(COALESCE(e.status,'')) IN ('POSTED','REVERSED')
                GROUP BY l.account_id
            ) sub ON sub.account_id = c.id
            WHERE c.account_code IN (""" + placeholders + ")", args.toArray());
    }

    private BigDecimal queryScalar(String sql, Object... args) {
        try {
            BigDecimal val = jdbc.queryForObject(sql, BigDecimal.class, args);
            return val != null ? val.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        } catch (Exception ignored) {
            return BigDecimal.ZERO;
        }
    }

    private static BigDecimal sumLinesByCode(List<FinancialStatementsDtos.StatementLineDto> lines, Set<String> codes) {
        return lines.stream()
                .filter(l -> l.accountCode() != null && codes.contains(l.accountCode()))
                .map(FinancialStatementsDtos.StatementLineDto::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static boolean isDirectCogs(String subtype, String code, String name) {
        String sub = subtype == null ? "" : subtype.toUpperCase(Locale.ROOT);
        String nm = name == null ? "" : name.toUpperCase(Locale.ROOT);
        String cd = code == null ? "" : code.trim();
        return sub.contains("COGS") || sub.contains("DIRECT") || sub.contains("PURCHASE")
                || nm.contains("COST OF GOODS") || nm.contains("PURCHASE") || nm.contains("FREIGHT IN")
                || cd.startsWith("501") || cd.startsWith("502") || cd.startsWith("503") || cd.equals("5100");
    }

    private static boolean isNonCurrentAsset(String subtype, String code) {
        String sub = subtype == null ? "" : subtype.toUpperCase(Locale.ROOT);
        String cd = code == null ? "" : code.trim();
        return sub.contains("FIXED") || sub.contains("NON_CURRENT") || cd.startsWith("15");
    }

    private static boolean isNonCurrentLiability(String subtype, String code) {
        String sub = subtype == null ? "" : subtype.toUpperCase(Locale.ROOT);
        String cd = code == null ? "" : code.trim();
        return sub.contains("LONG_TERM") || sub.contains("NON_CURRENT") || cd.startsWith("25");
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue()).setScale(2, RoundingMode.HALF_UP);
        try {
            return new BigDecimal(v.toString().trim()).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }
}
