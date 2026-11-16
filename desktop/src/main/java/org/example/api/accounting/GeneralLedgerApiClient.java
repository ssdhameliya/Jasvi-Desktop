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
        return get("/api/accounting/accounts", new TypeReference<List<AccountDto>>() {});
    }

    public AccountDto createAccount(AccountDto dto) {
        return post("/api/accounting/accounts", dto, AccountDto.class);
    }

    public List<JournalEntryDto> journalEntries() {
        return get("/api/accounting/journal-entries", new TypeReference<List<JournalEntryDto>>() {});
    }

    public JournalEntryDto postJournalEntry(CreateJournalRequest req) {
        return post("/api/accounting/journal-entries", req, JournalEntryDto.class);
    }

    public JournalEntryDto postDraft(Long id, String postedBy) {
        String p = "/api/accounting/journal-entries/" + id + "/post" + (postedBy != null ? "?postedBy=" + enc(postedBy) : "");
        return post(p, null, JournalEntryDto.class);
    }

    public List<TrialBalanceItemDto> trialBalance() {
        return get("/api/accounting/trial-balance", new TypeReference<List<TrialBalanceItemDto>>() {});
    }

    public FinancialStatementDto financialStatements() {
        return get("/api/accounting/financial-statements", FinancialStatementDto.class);
    }

    public PostingFlowResponse postingFlow(String docType, String docNo) {
        String p = "/api/accounting/posting-flow?docType=" + enc(docType) + "&docNo=" + enc(docNo);
        return get(p, PostingFlowResponse.class);
    }

    private <T> T get(String p, Class<T> c) { return req("GET", p, null, c, null); }
    private <T> T get(String p, TypeReference<T> t) { return req("GET", p, null, null, t); }
    private <T> T post(String p, Object b, Class<T> c) { return req("POST", p, b, c, null); }
    private <T> T post(String p, Object b, TypeReference<T> t) { return req("POST", p, b, null, t); }

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
            BigDecimal sellingRate,
            BigDecimal lineRevenue,
            BigDecimal lineProfit,
            BigDecimal profitMarginPercent
    ) {
        public PostingFlowInventoryLine(String itemCode, String description, String movementType,
                                        BigDecimal quantity, String unit, BigDecimal unitCost, BigDecimal totalValuation) {
            this(itemCode, description, movementType, quantity, unit, unitCost, totalValuation,
                 null, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
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
            BigDecimal orderProfitMarginPercent
    ) {
        public PostingFlowResponse(String documentType, String documentNo, LocalDate documentDate, String partyName,
                                   String partyGstin, BigDecimal totalAmount, String postingProfile, String summaryDescription,
                                   String journalVoucherNo, List<PostingFlowVoucherLine> journalLines, BigDecimal totalDebit,
                                   BigDecimal totalCredit, boolean isBalanced, List<PostingFlowInventoryLine> inventoryMovements,
                                   PostingFlowGstImpact gstImpact) {
            this(documentType, documentNo, documentDate, partyName, partyGstin, totalAmount, postingProfile, summaryDescription,
                 journalVoucherNo, journalLines, totalDebit, totalCredit, isBalanced, inventoryMovements, gstImpact,
                 BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }
}
