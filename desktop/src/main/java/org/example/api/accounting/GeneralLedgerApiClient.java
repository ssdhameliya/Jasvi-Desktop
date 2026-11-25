package org.example.api.accounting;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.ConfigManager;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class GeneralLedgerApiClient {
    private final HttpClient http = org.example.api.ApiRuntime.HTTP;
    private final ObjectMapper json = org.example.api.ApiRuntime.JSON;
    private final String base;

    public GeneralLedgerApiClient() {
        String b = ConfigManager.getDataApiBaseUrl();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        base = b;
    }

    public List<AccountDto> accounts() {
        return accounts(false);
    }

    public List<AccountDto> accounts(boolean includeInactive) {
        String p = "/api/accounting/accounts" + (includeInactive ? "?includeInactive=true" : "");
        return get(p, new TypeReference<List<AccountDto>>() {});
    }

    public AccountDto createAccount(AccountDto dto) {
        return post("/api/accounting/accounts", dto, AccountDto.class);
    }

    public AccountDto updateAccount(Long id, UpdateAccountRequest req) {
        return put("/api/accounting/accounts/" + id, req, AccountDto.class);
    }

    public List<JournalEntryDto> journalEntries() {
        return journalEntries(null, null, null, null);
    }

    public List<JournalEntryDto> journalEntries(LocalDate fromDate, LocalDate toDate, String entryType, String status) {
        StringBuilder p = new StringBuilder("/api/accounting/journal-entries");
        String sep = "?";
        if (fromDate != null) { p.append(sep).append("fromDate=").append(enc(fromDate.toString())); sep = "&"; }
        if (toDate != null) { p.append(sep).append("toDate=").append(enc(toDate.toString())); sep = "&"; }
        if (entryType != null && !entryType.isBlank()) { p.append(sep).append("entryType=").append(enc(entryType)); sep = "&"; }
        if (status != null && !status.isBlank()) { p.append(sep).append("status=").append(enc(status)); }
        return get(p.toString(), new TypeReference<List<JournalEntryDto>>() {});
    }

    public PagedJournalResponse journalEntriesPage(int page, int size, LocalDate fromDate, LocalDate toDate,
                                                     String entryType, String status, String q) {
        StringBuilder p = new StringBuilder("/api/accounting/journal-entries/page?page=").append(page)
                .append("&size=").append(size);
        if (fromDate != null) p.append("&fromDate=").append(enc(fromDate.toString()));
        if (toDate != null) p.append("&toDate=").append(enc(toDate.toString()));
        if (entryType != null && !entryType.isBlank()) p.append("&entryType=").append(enc(entryType));
        if (status != null && !status.isBlank()) p.append("&status=").append(enc(status));
        if (q != null && !q.isBlank()) p.append("&q=").append(enc(q));
        return get(p.toString(), PagedJournalResponse.class);
    }

    public PartyLedgerResponse partyLedger(long partyId, LocalDate fromDate, LocalDate toDate) {
        StringBuilder p = new StringBuilder("/api/accounting/party-ledger/").append(partyId);
        String sep = "?";
        if (fromDate != null) { p.append(sep).append("fromDate=").append(enc(fromDate.toString())); sep = "&"; }
        if (toDate != null) { p.append(sep).append("toDate=").append(enc(toDate.toString())); }
        return get(p.toString(), PartyLedgerResponse.class);
    }

    public ReconciliationHealthCheckDto reconciliationHealth() {
        return get("/api/accounting/reconciliation-health", ReconciliationHealthCheckDto.class);
    }

    public FiscalYearCloseResponse closeFiscalYear(FiscalYearCloseRequest req) {
        return post("/api/accounting/fiscal-year-close", req, FiscalYearCloseResponse.class);
    }

    public JournalEntryDto postJournalEntry(CreateJournalRequest req) {
        return post("/api/accounting/journal-entries", req, JournalEntryDto.class);
    }

    public JournalEntryDto postDraft(Long id, String postedBy) {
        String p = "/api/accounting/journal-entries/" + id + "/post" + (postedBy != null ? "?postedBy=" + enc(postedBy) : "");
        return post(p, null, JournalEntryDto.class);
    }

    public JournalEntryDto reverseJournalEntry(Long id, ReverseJournalRequest req) {
        return post("/api/accounting/journal-entries/" + id + "/reverse", req, JournalEntryDto.class);
    }

    public List<TrialBalanceItemDto> trialBalance() {
        return trialBalance(null, null);
    }

    public List<TrialBalanceItemDto> trialBalance(LocalDate fromDate, LocalDate toDate) {
        StringBuilder p = new StringBuilder("/api/accounting/trial-balance");
        String sep = "?";
        if (fromDate != null) { p.append(sep).append("fromDate=").append(enc(fromDate.toString())); sep = "&"; }
        if (toDate != null) { p.append(sep).append("toDate=").append(enc(toDate.toString())); }
        return get(p.toString(), new TypeReference<List<TrialBalanceItemDto>>() {});
    }

    public FinancialStatementDto financialStatements() {
        return financialStatements(null, null);
    }

    public FinancialStatementDto financialStatements(LocalDate fromDate, LocalDate toDate) {
        StringBuilder p = new StringBuilder("/api/accounting/financial-statements");
        String sep = "?";
        if (fromDate != null) { p.append(sep).append("fromDate=").append(enc(fromDate.toString())); sep = "&"; }
        if (toDate != null) { p.append(sep).append("toDate=").append(enc(toDate.toString())); }
        return get(p.toString(), FinancialStatementDto.class);
    }

    public AccountLedgerResponse accountLedger(String accountCode, LocalDate fromDate, LocalDate toDate) {
        StringBuilder p = new StringBuilder("/api/accounting/account-ledger/").append(enc(accountCode));
        String sep = "?";
        if (fromDate != null) { p.append(sep).append("fromDate=").append(enc(fromDate.toString())); sep = "&"; }
        if (toDate != null) { p.append(sep).append("toDate=").append(enc(toDate.toString())); }
        return get(p.toString(), AccountLedgerResponse.class);
    }

    public ProfitAndLossResponse profitAndLoss(LocalDate fromDate, LocalDate toDate) {
        StringBuilder p = new StringBuilder("/api/accounting/profit-and-loss");
        String sep = "?";
        if (fromDate != null) { p.append(sep).append("fromDate=").append(enc(fromDate.toString())); sep = "&"; }
        if (toDate != null) { p.append(sep).append("toDate=").append(enc(toDate.toString())); }
        return get(p.toString(), ProfitAndLossResponse.class);
    }

    public BalanceSheetResponse balanceSheet(LocalDate asOfDate) {
        String p = "/api/accounting/balance-sheet" + (asOfDate != null ? "?asOfDate=" + enc(asOfDate.toString()) : "");
        return get(p, BalanceSheetResponse.class);
    }

    public PeriodLockDto periodLock() {
        return get("/api/accounting/period-lock", PeriodLockDto.class);
    }

    public PeriodLockDto setPeriodLock(LocalDate lockedUntil) {
        return put("/api/accounting/period-lock", new PeriodLockDto(lockedUntil, null, null, lockedUntil != null), PeriodLockDto.class);
    }

    public PostingFlowResponse postingFlow(String docType, String docNo) {
        String p = "/api/accounting/posting-flow?docType=" + enc(docType) + "&docNo=" + enc(docNo);
        return get(p, PostingFlowResponse.class);
    }

    private <T> T get(String p, Class<T> c) { return req("GET", p, null, c, null); }
    private <T> T get(String p, TypeReference<T> t) { return req("GET", p, null, null, t); }
    private <T> T post(String p, Object b, Class<T> c) { return req("POST", p, b, c, null); }
    private <T> T post(String p, Object b, TypeReference<T> t) { return req("POST", p, b, null, t); }
    private <T> T put(String p, Object b, Class<T> c) { return req("PUT", p, b, c, null); }

    private <T> T req(String method, String path, Object body, Class<T> c, TypeReference<T> t) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json");
            org.example.api.ApiSession.authorize(b);
            if (body != null) b.header("Content-Type", "application/json");
            String payload = body == null ? "" : json.writeValueAsString(body);
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() / 100 != 2) {
                org.example.api.ApiRuntime.logHttpFailure("General Ledger request", r.statusCode(), r.body());
                throw new IllegalStateException(org.example.api.ApiRuntime.userMessage("the General Ledger request", r.statusCode(), r.body()));
            }
            if (c != null) return json.readValue(r.body(), c);
            return json.readValue(r.body(), t);
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new IllegalStateException("Cannot reach General Ledger API at " + base + ": " + e.getMessage(), e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    public record AccountDto(
            Long id,
            String accountCode,
            String accountName,
            String accountType,
            String accountSubtype,
            Long parentId,
            String currency,
            BigDecimal openingBalance,
            BigDecimal currentBalance,
            Boolean isActive,
            Boolean isSystem,
            Long rowVersion
    ) {}

    public record CreateLineRequest(
            Long accountId,
            String accountCode,
            BigDecimal debitAmount,
            BigDecimal creditAmount,
            Long partyId,
            String lineNarration,
            String costCenter
    ) {}

    public record CreateJournalRequest(
            LocalDate entryDate,
            String entryType,
            String referenceType,
            Long referenceId,
            String referenceNo,
            String narration,
            String postedBy,
            String status,
            List<CreateLineRequest> lines
    ) {
        public CreateJournalRequest(
                LocalDate entryDate,
                String entryType,
                String referenceType,
                Long referenceId,
                String referenceNo,
                String narration,
                String postedBy,
                List<CreateLineRequest> lines
        ) {
            this(entryDate, entryType, referenceType, referenceId, referenceNo, narration, postedBy, "POSTED", lines);
        }
    }

    public record JournalLineDto(
            Long id,
            Long accountId,
            String accountCode,
            String accountName,
            Integer lineNumber,
            BigDecimal debitAmount,
            BigDecimal creditAmount,
            Long partyId,
            String lineNarration,
            String costCenter
    ) {}

    public record JournalEntryDto(
            Long id,
            String entryNumber,
            LocalDate entryDate,
            String entryType,
            String referenceType,
            Long referenceId,
            String referenceNo,
            String narration,
            BigDecimal totalDebit,
            BigDecimal totalCredit,
            String status,
            String postedBy,
            LocalDateTime postedAt,
            Long rowVersion,
            List<JournalLineDto> lines
    ) {}

    public record TrialBalanceItemDto(
            String accountCode,
            String accountName,
            String accountType,
            BigDecimal openingBalance,
            BigDecimal debitAmount,
            BigDecimal creditAmount,
            BigDecimal closingBalance
    ) {}

    public record FinancialStatementDto(
            BigDecimal totalRevenue,
            BigDecimal totalExpense,
            BigDecimal netProfit,
            BigDecimal totalAssets,
            BigDecimal totalLiabilities,
            BigDecimal totalEquity
    ) {}

    public record UpdateAccountRequest(
            String accountName,
            String accountType,
            String accountSubtype,
            BigDecimal openingBalance,
            Boolean isActive
    ) {}

    public record ReverseJournalRequest(
            LocalDate reversalDate,
            String reason,
            String postedBy
    ) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record PeriodLockDto(
            LocalDate lockedUntil,
            String lockedBy,
            String updatedAt,
            boolean active
    ) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record AccountLedgerLineDto(
            Long journalEntryId,
            String entryNumber,
            LocalDate entryDate,
            String entryType,
            String referenceType,
            Long referenceId,
            String referenceNo,
            String narration,
            String lineNarration,
            BigDecimal debitAmount,
            BigDecimal creditAmount,
            BigDecimal runningBalance,
            String balanceType,
            String status,
            String postedBy
    ) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record AccountLedgerResponse(
            AccountDto account,
            LocalDate fromDate,
            LocalDate toDate,
            BigDecimal openingBalance,
            BigDecimal totalDebit,
            BigDecimal totalCredit,
            BigDecimal closingBalance,
            String closingBalanceType,
            List<AccountLedgerLineDto> entries
    ) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record ProfitAndLossLineDto(
            String accountCode,
            String accountName,
            String subtype,
            BigDecimal amount
    ) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record ProfitAndLossResponse(
            LocalDate fromDate,
            LocalDate toDate,
            List<ProfitAndLossLineDto> revenueLines,
            List<ProfitAndLossLineDto> directExpenseLines,
            List<ProfitAndLossLineDto> indirectExpenseLines,
            BigDecimal totalRevenue,
            BigDecimal totalDirectExpense,
            BigDecimal grossProfit,
            BigDecimal grossMarginPercent,
            BigDecimal totalIndirectExpense,
            BigDecimal netProfit,
            BigDecimal netMarginPercent
    ) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record BalanceSheetLineDto(
            String accountCode,
            String accountName,
            String subtype,
            BigDecimal balance
    ) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record BalanceSheetResponse(
            LocalDate asOfDate,
            List<BalanceSheetLineDto> assetLines,
            List<BalanceSheetLineDto> liabilityLines,
            List<BalanceSheetLineDto> equityLines,
            BigDecimal totalAssets,
            BigDecimal totalLiabilities,
            BigDecimal totalEquity,
            BigDecimal currentPeriodNetProfit,
            BigDecimal totalLiabilitiesAndEquity,
            boolean balanced,
            BigDecimal difference
    ) {}

    public record PostingFlowVoucherLine(
            String accountCode,
            String accountName,
            BigDecimal debitAmount,
            BigDecimal creditAmount,
            String narration
    ) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record PostingFlowInventoryLine(
            String itemCode,
            String description,
            String movementType,
            BigDecimal quantity,
            String unit,
            BigDecimal unitCost,
            BigDecimal totalValuation,
            BigDecimal stockBefore,
            BigDecimal stockAfter,
            BigDecimal grossRate,
            BigDecimal discountPercent,
            BigDecimal discountAmount,
            BigDecimal sellingRate,
            BigDecimal lineRevenue,
            BigDecimal lineProfit,
            BigDecimal profitMarginPercent
    ) {
        public PostingFlowInventoryLine(String itemCode, String description, String movementType,
                                        BigDecimal quantity, String unit, BigDecimal unitCost, BigDecimal totalValuation,
                                        BigDecimal stockBefore, BigDecimal stockAfter,
                                        BigDecimal sellingRate, BigDecimal lineRevenue, BigDecimal lineProfit,
                                        BigDecimal profitMarginPercent) {
            this(itemCode, description, movementType, quantity, unit, unitCost, totalValuation,
                 stockBefore, stockAfter, sellingRate, BigDecimal.ZERO, BigDecimal.ZERO,
                 sellingRate, lineRevenue, lineProfit, profitMarginPercent);
        }

        public PostingFlowInventoryLine(String itemCode, String description, String movementType,
                                        BigDecimal quantity, String unit, BigDecimal unitCost, BigDecimal totalValuation) {
            this(itemCode, description, movementType, quantity, unit, unitCost, totalValuation,
                 null, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                 BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record PostingFlowGstImpact(
            String returnForm,
            String tableSection,
            BigDecimal taxableAmount,
            BigDecimal cgstAmount,
            BigDecimal sgstAmount,
            BigDecimal igstAmount,
            BigDecimal totalTax,
            String statutoryRule
    ) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record PostingFlowResponse(
            String documentType,
            String documentNo,
            LocalDate documentDate,
            String partyName,
            String partyGstin,
            BigDecimal totalAmount,
            String postingProfile,
            String summaryDescription,
            String journalVoucherNo,
            List<PostingFlowVoucherLine> journalLines,
            BigDecimal totalDebit,
            BigDecimal totalCredit,
            boolean isBalanced,
            List<PostingFlowInventoryLine> inventoryMovements,
            PostingFlowGstImpact gstImpact,
            BigDecimal totalTaxableRevenue,
            BigDecimal totalInventoryCost,
            BigDecimal totalOrderProfit,
            BigDecimal orderProfitMarginPercent,
            BigDecimal totalDiscount,
            LocalDateTime postedAt,
            String postedBy,
            String triggerEvent,
            String referenceNo,
            String paymentStatus,
            BigDecimal paidAmount
    ) {
        public PostingFlowResponse(String documentType, String documentNo, LocalDate documentDate, String partyName,
                                   String partyGstin, BigDecimal totalAmount, String postingProfile, String summaryDescription,
                                   String journalVoucherNo, List<PostingFlowVoucherLine> journalLines, BigDecimal totalDebit,
                                   BigDecimal totalCredit, boolean isBalanced, List<PostingFlowInventoryLine> inventoryMovements,
                                   PostingFlowGstImpact gstImpact, BigDecimal totalTaxableRevenue, BigDecimal totalInventoryCost,
                                   BigDecimal totalOrderProfit, BigDecimal orderProfitMarginPercent, BigDecimal totalDiscount,
                                   LocalDateTime postedAt, String postedBy, String triggerEvent) {
            this(documentType, documentNo, documentDate, partyName, partyGstin, totalAmount, postingProfile, summaryDescription,
                 journalVoucherNo, journalLines, totalDebit, totalCredit, isBalanced, inventoryMovements, gstImpact,
                 totalTaxableRevenue, totalInventoryCost, totalOrderProfit, orderProfitMarginPercent, totalDiscount,
                 postedAt, postedBy, triggerEvent, null, null, null);
        }

        public PostingFlowResponse(String documentType, String documentNo, LocalDate documentDate, String partyName,
                                   String partyGstin, BigDecimal totalAmount, String postingProfile, String summaryDescription,
                                   String journalVoucherNo, List<PostingFlowVoucherLine> journalLines, BigDecimal totalDebit,
                                   BigDecimal totalCredit, boolean isBalanced, List<PostingFlowInventoryLine> inventoryMovements,
                                   PostingFlowGstImpact gstImpact, BigDecimal totalTaxableRevenue, BigDecimal totalInventoryCost,
                                   BigDecimal totalOrderProfit, BigDecimal orderProfitMarginPercent) {
            this(documentType, documentNo, documentDate, partyName, partyGstin, totalAmount, postingProfile, summaryDescription,
                 journalVoucherNo, journalLines, totalDebit, totalCredit, isBalanced, inventoryMovements, gstImpact,
                 totalTaxableRevenue, totalInventoryCost, totalOrderProfit, orderProfitMarginPercent,
                 BigDecimal.ZERO, null, "SYSTEM", "AUTO_POST", null, null, null);
        }

        public PostingFlowResponse(String documentType, String documentNo, LocalDate documentDate, String partyName,
                                   String partyGstin, BigDecimal totalAmount, String postingProfile, String summaryDescription,
                                   String journalVoucherNo, List<PostingFlowVoucherLine> journalLines, BigDecimal totalDebit,
                                   BigDecimal totalCredit, boolean isBalanced, List<PostingFlowInventoryLine> inventoryMovements,
                                   PostingFlowGstImpact gstImpact) {
            this(documentType, documentNo, documentDate, partyName, partyGstin, totalAmount, postingProfile, summaryDescription,
                 journalVoucherNo, journalLines, totalDebit, totalCredit, isBalanced, inventoryMovements, gstImpact,
                 BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                 BigDecimal.ZERO, null, "SYSTEM", "AUTO_POST", null, null, null);
        }
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record PagedJournalResponse(
            List<JournalEntryDto> rows,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record PartyLedgerResponse(
            Long partyId,
            String partyName,
            String partyType,
            LocalDate fromDate,
            LocalDate toDate,
            BigDecimal openingBalance,
            BigDecimal totalDebit,
            BigDecimal totalCredit,
            BigDecimal closingBalance,
            String closingBalanceType,
            List<AccountLedgerLineDto> entries
    ) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record ReconciliationControlRow(
            String area,
            String accountCode,
            String accountName,
            BigDecimal glBalance,
            BigDecimal subledgerBalance,
            BigDecimal variance,
            String status
    ) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record UnpostedDocRow(
            String docType,
            Long docId,
            String docNo,
            String date,
            BigDecimal amount,
            String status
    ) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record ReconciliationHealthCheckDto(
            List<ReconciliationControlRow> controls,
            List<UnpostedDocRow> unpostedDocuments,
            int mismatchCount,
            int unpostedCount,
            boolean healthy
    ) {}

    public record FiscalYearCloseRequest(
            String fyLabel,
            LocalDate fromDate,
            LocalDate toDate,
            boolean lockPeriod,
            String closedBy
    ) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record FiscalYearCloseResponse(
            Long closingEntryId,
            String entryNumber,
            String fyLabel,
            BigDecimal netProfitTransferred,
            LocalDate lockedUntil,
            String message
    ) {}
}
