package org.example.server.accounting;

import org.example.server.security.CurrentUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/accounting")
public class GeneralLedgerController {

    private final GeneralLedgerService service;

    public GeneralLedgerController(GeneralLedgerService service) {
        this.service = service;
    }

    @GetMapping("/accounts")
    public List<GeneralLedgerDtos.AccountDto> listAccounts(
            @RequestParam(value = "includeInactive", required = false, defaultValue = "false") boolean includeInactive) {
        CurrentUser.requirePermission("GENERAL_LEDGER.VIEW", "View Chart of Accounts");
        return service.listAccounts(includeInactive);
    }

    @PostMapping("/accounts")
    public GeneralLedgerDtos.AccountDto createAccount(@RequestBody GeneralLedgerDtos.AccountDto dto) {
        CurrentUser.requirePermission("GENERAL_LEDGER.CREATE_ACCOUNT", "Create ledger account");
        return service.createAccount(dto);
    }

    @PutMapping("/accounts/{id}")
    public GeneralLedgerDtos.AccountDto updateAccount(
            @PathVariable("id") Long id,
            @RequestBody GeneralLedgerDtos.UpdateAccountRequest req) {
        CurrentUser.requirePermission("GENERAL_LEDGER.EDIT_ACCOUNT", "Edit ledger account");
        return service.updateAccount(id, req);
    }

    @GetMapping("/journal-entries")
    public List<GeneralLedgerDtos.JournalEntryDto> listEntries(
            @RequestParam(value = "fromDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(value = "toDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(value = "entryType", required = false) String entryType,
            @RequestParam(value = "status", required = false) String status) {
        CurrentUser.requirePermission("GENERAL_LEDGER.VIEW", "View journal entries");
        return service.listEntries(fromDate, toDate, entryType, status);
    }

    @GetMapping("/journal-entries/page")
    public GeneralLedgerDtos.PagedJournalResponse listEntriesPage(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size,
            @RequestParam(value = "fromDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(value = "toDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(value = "entryType", required = false) String entryType,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "q", required = false) String q) {
        CurrentUser.requirePermission("GENERAL_LEDGER.VIEW", "View paged journal entries");
        return service.listEntriesPage(page, size, fromDate, toDate, entryType, status, q);
    }

    @PostMapping("/journal-entries")
    public GeneralLedgerDtos.JournalEntryDto postEntry(@RequestBody GeneralLedgerDtos.CreateJournalRequest req) {
        CurrentUser.requirePermission("GENERAL_LEDGER.CREATE_JOURNAL", "Create journal entry");
        return service.postJournalEntry(req);
    }

    @PostMapping("/journal-entries/{id}/post")
    public GeneralLedgerDtos.JournalEntryDto postDraft(
            @PathVariable("id") Long id,
            @RequestParam(value = "postedBy", required = false) String postedBy) {
        CurrentUser.requirePermission("GENERAL_LEDGER.POST_JOURNAL", "Post draft journal entry");
        return service.postDraft(id, postedBy);
    }

    @PostMapping("/journal-entries/{id}/reverse")
    public GeneralLedgerDtos.JournalEntryDto reverseEntry(
            @PathVariable("id") Long id,
            @RequestBody(required = false) GeneralLedgerDtos.ReverseJournalRequest req) {
        CurrentUser.requirePermission("GENERAL_LEDGER.REVERSE_JOURNAL", "Reverse posted journal entry");
        return service.reverseJournalEntry(id, req);
    }

    @GetMapping("/trial-balance")
    public List<GeneralLedgerDtos.TrialBalanceItemDto> trialBalance(
            @RequestParam(value = "fromDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(value = "toDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        CurrentUser.requirePermission("GENERAL_LEDGER.VIEW", "View Trial Balance");
        return service.getTrialBalance(fromDate, toDate);
    }

    @GetMapping("/financial-statements")
    public GeneralLedgerDtos.FinancialStatementDto financialStatements(
            @RequestParam(value = "fromDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(value = "toDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        CurrentUser.requirePermission("GENERAL_LEDGER.VIEW", "View GL Financial Summary");
        return service.getFinancialStatements(fromDate, toDate);
    }

    @GetMapping("/account-ledger/{accountCode}")
    public GeneralLedgerDtos.AccountLedgerResponse accountLedger(
            @PathVariable("accountCode") String accountCode,
            @RequestParam(value = "fromDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(value = "toDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        CurrentUser.requirePermission("GENERAL_LEDGER.VIEW", "View Account Ledger");
        return service.getAccountLedger(accountCode, fromDate, toDate);
    }

    @GetMapping("/party-ledger/{partyId}")
    public GeneralLedgerDtos.PartyLedgerResponse partyLedger(
            @PathVariable("partyId") Long partyId,
            @RequestParam(value = "fromDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(value = "toDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        CurrentUser.requirePermission("GENERAL_LEDGER.VIEW", "View Party Sub-Ledger");
        return service.getPartyLedger(partyId, fromDate, toDate);
    }

    @GetMapping("/reconciliation-health")
    public GeneralLedgerDtos.ReconciliationHealthCheckDto reconciliationHealth() {
        CurrentUser.requirePermission("GENERAL_LEDGER.VIEW", "View GL Reconciliation Health");
        return service.getReconciliationHealth();
    }

    @PostMapping("/fiscal-year-close")
    public GeneralLedgerDtos.FiscalYearCloseResponse closeFiscalYear(
            @RequestBody GeneralLedgerDtos.FiscalYearCloseRequest req) {
        CurrentUser.requirePermission("GENERAL_LEDGER.CLOSE_YEAR", "Close Fiscal Year");
        String actor = CurrentUser.require().username();
        return service.closeFiscalYear(req, actor);
    }

    @GetMapping("/profit-and-loss")
    public GeneralLedgerDtos.ProfitAndLossResponse profitAndLoss(
            @RequestParam(value = "fromDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(value = "toDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        CurrentUser.requirePermission("GENERAL_LEDGER.VIEW", "View GL Profit & Loss");
        return service.getProfitAndLoss(fromDate, toDate);
    }

    @GetMapping("/balance-sheet")
    public GeneralLedgerDtos.BalanceSheetResponse balanceSheet(
            @RequestParam(value = "asOfDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate) {
        CurrentUser.requirePermission("GENERAL_LEDGER.VIEW", "View GL Balance Sheet");
        return service.getBalanceSheet(asOfDate);
    }

    @GetMapping("/period-lock")
    public GeneralLedgerDtos.PeriodLockDto getPeriodLock() {
        CurrentUser.requirePermission("GENERAL_LEDGER.VIEW", "View Accounting Period Lock");
        return service.getPeriodLock();
    }

    @PutMapping("/period-lock")
    public GeneralLedgerDtos.PeriodLockDto setPeriodLock(@RequestBody GeneralLedgerDtos.PeriodLockDto req) {
        String role = CurrentUser.require().role();
        if (!"ADMIN".equalsIgnoreCase(role) && !"SUPER_ADMIN".equalsIgnoreCase(role)) {
            CurrentUser.requirePermission("GENERAL_LEDGER.LOCK_PERIOD", "Lock/Unlock Accounting Period");
        }
        String actor = CurrentUser.require().username();
        return service.setPeriodLock(req != null ? req.lockedUntil() : null, actor);
    }

    @GetMapping("/posting-flow/{docType}/{docNo}")
    public GeneralLedgerDtos.PostingFlowResponse postingFlowByPath(
            @PathVariable("docType") String docType,
            @PathVariable("docNo") String docNo) {
        CurrentUser.requirePermission("GENERAL_LEDGER.VIEW", "Inspect Accounting Posting Flow");
        return service.getPostingFlow(docType, docNo);
    }

    @GetMapping("/posting-flow")
    public GeneralLedgerDtos.PostingFlowResponse postingFlowByQuery(
            @RequestParam("docType") String docType,
            @RequestParam("docNo") String docNo) {
        CurrentUser.requirePermission("GENERAL_LEDGER.VIEW", "Inspect Accounting Posting Flow");
        return service.getPostingFlow(docType, docNo);
    }
}
