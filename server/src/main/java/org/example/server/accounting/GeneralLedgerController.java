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
        return service.listAccounts(includeInactive);
    }

    @PostMapping("/accounts")
    public GeneralLedgerDtos.AccountDto createAccount(@RequestBody GeneralLedgerDtos.AccountDto dto) {
        return service.createAccount(dto);
    }

    @PutMapping("/accounts/{id}")
    public GeneralLedgerDtos.AccountDto updateAccount(
            @PathVariable("id") Long id,
            @RequestBody GeneralLedgerDtos.UpdateAccountRequest req) {
        return service.updateAccount(id, req);
    }

    @GetMapping("/journal-entries")
    public List<GeneralLedgerDtos.JournalEntryDto> listEntries(
            @RequestParam(value = "fromDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(value = "toDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(value = "entryType", required = false) String entryType,
            @RequestParam(value = "status", required = false) String status) {
        return service.listEntries(fromDate, toDate, entryType, status);
    }

    @PostMapping("/journal-entries")
    public GeneralLedgerDtos.JournalEntryDto postEntry(@RequestBody GeneralLedgerDtos.CreateJournalRequest req) {
        return service.postJournalEntry(req);
    }

    @PostMapping("/journal-entries/{id}/post")
    public GeneralLedgerDtos.JournalEntryDto postDraft(
            @PathVariable("id") Long id,
            @RequestParam(value = "postedBy", required = false) String postedBy) {
        return service.postDraft(id, postedBy);
    }

    @PostMapping("/journal-entries/{id}/reverse")
    public GeneralLedgerDtos.JournalEntryDto reverseEntry(
            @PathVariable("id") Long id,
            @RequestBody(required = false) GeneralLedgerDtos.ReverseJournalRequest req) {
        return service.reverseJournalEntry(id, req);
    }

    @GetMapping("/trial-balance")
    public List<GeneralLedgerDtos.TrialBalanceItemDto> trialBalance(
            @RequestParam(value = "fromDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(value = "toDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return service.getTrialBalance(fromDate, toDate);
    }

    @GetMapping("/financial-statements")
    public GeneralLedgerDtos.FinancialStatementDto financialStatements(
            @RequestParam(value = "fromDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(value = "toDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return service.getFinancialStatements(fromDate, toDate);
    }

    @GetMapping("/account-ledger/{accountCode}")
    public GeneralLedgerDtos.AccountLedgerResponse accountLedger(
            @PathVariable("accountCode") String accountCode,
            @RequestParam(value = "fromDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(value = "toDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return service.getAccountLedger(accountCode, fromDate, toDate);
    }

    @GetMapping("/profit-and-loss")
    public GeneralLedgerDtos.ProfitAndLossResponse profitAndLoss(
            @RequestParam(value = "fromDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(value = "toDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return service.getProfitAndLoss(fromDate, toDate);
    }

    @GetMapping("/balance-sheet")
    public GeneralLedgerDtos.BalanceSheetResponse balanceSheet(
            @RequestParam(value = "asOfDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate) {
        return service.getBalanceSheet(asOfDate);
    }

    @GetMapping("/period-lock")
    public GeneralLedgerDtos.PeriodLockDto getPeriodLock() {
        return service.getPeriodLock();
    }

    @PutMapping("/period-lock")
    public GeneralLedgerDtos.PeriodLockDto setPeriodLock(@RequestBody GeneralLedgerDtos.PeriodLockDto req) {
        String role = CurrentUser.require().role();
        if (!"ADMIN".equalsIgnoreCase(role) && !"SUPER_ADMIN".equalsIgnoreCase(role)) {
            throw new SecurityException("Accounting Period Lock requires Administrator access.");
        }
        String actor = CurrentUser.require().username();
        return service.setPeriodLock(req != null ? req.lockedUntil() : null, actor);
    }

    @GetMapping("/posting-flow/{docType}/{docNo}")
    public GeneralLedgerDtos.PostingFlowResponse postingFlowByPath(
            @PathVariable("docType") String docType,
            @PathVariable("docNo") String docNo) {
        return service.getPostingFlow(docType, docNo);
    }

    @GetMapping("/posting-flow")
    public GeneralLedgerDtos.PostingFlowResponse postingFlowByQuery(
            @RequestParam("docType") String docType,
            @RequestParam("docNo") String docNo) {
        return service.getPostingFlow(docType, docNo);
    }
}
