package org.example.server.financialstatements;

import org.example.server.security.CurrentUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping({"/api/financial-statements", "/api/financial"})
public class FinancialStatementsController {

    private final FinancialStatementsService service;

    public FinancialStatementsController(FinancialStatementsService service) {
        this.service = service;
    }

    private static void requireViewPermission(String detail) {
        if (!CurrentUser.hasPermission("FINANCIAL_STATEMENTS.VIEW")
                && !CurrentUser.hasPermission("GENERAL_LEDGER.VIEW")) {
            CurrentUser.requirePermission("FINANCIAL_STATEMENTS.VIEW", detail);
        }
    }

    @GetMapping("/pnl")
    public FinancialStatementsDtos.ProfitAndLossDto getProfitAndLoss(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        requireViewPermission("View Profit & Loss statement");
        return service.getProfitAndLoss(fromDate, toDate);
    }

    @GetMapping("/profit-and-loss")
    public FinancialStatementsDtos.DesktopProfitAndLossDto getDesktopProfitAndLoss(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        requireViewPermission("View Desktop Profit & Loss statement");
        return service.getDesktopProfitAndLoss(fromDate, toDate);
    }

    @GetMapping("/balance-sheet")
    public FinancialStatementsDtos.DesktopBalanceSheetDto getBalanceSheet(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate) {
        requireViewPermission("View Balance Sheet statement");
        return service.getDesktopBalanceSheet(asOfDate);
    }

    @GetMapping("/three-tier")
    public FinancialStatementsDtos.ThreeTierFinancialStatementDto getThreeTierStatements(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false, defaultValue = "OPERATIONAL") String engine) {
        requireViewPermission("View Financial Statements");
        return service.getThreeTierStatements(fromDate, toDate, engine);
    }

    @GetMapping("/drill-down")
    public FinancialStatementsDtos.DrillDownResponseDto getDrillDown(
            @RequestParam(required = false, defaultValue = "SALES") String category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        requireViewPermission("Inspect financial statement drill-down");
        return service.getDrillDown(category, fromDate, toDate);
    }

    @GetMapping("/ratios")
    public FinancialStatementsDtos.FinancialRatiosDto getRatios(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        requireViewPermission("View executive financial ratios");
        return service.getThreeTierStatements(fromDate, toDate, "OPERATIONAL").ratios();
    }
}
