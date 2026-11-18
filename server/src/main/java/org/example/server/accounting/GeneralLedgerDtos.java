package org.example.server.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class GeneralLedgerDtos {

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

    public record JournalLineDto(
            Long id,
            Long accountId,
            String accountCode,
            String accountName,
            Integer lineNumber,
            BigDecimal debitAmount,
            BigDecimal creditAmount,
            Integer partyId,
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

    public record CreateLineRequest(
            Long accountId,
            String accountCode,
            BigDecimal debitAmount,
            BigDecimal creditAmount,
            Integer partyId,
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

    public record PeriodLockDto(
            LocalDate lockedUntil,
            String lockedBy,
            String updatedAt,
            boolean active
    ) {}

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

    public record ProfitAndLossLineDto(
            String accountCode,
            String accountName,
            String subtype,
            BigDecimal amount
    ) {}

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

    public record BalanceSheetLineDto(
            String accountCode,
            String accountName,
            String subtype,
            BigDecimal balance
    ) {}

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
            String triggerEvent
    ) {
        public PostingFlowResponse(String documentType, String documentNo, LocalDate documentDate, String partyName,
                                   String partyGstin, BigDecimal totalAmount, String postingProfile, String summaryDescription,
                                   String journalVoucherNo, List<PostingFlowVoucherLine> journalLines, BigDecimal totalDebit,
                                   BigDecimal totalCredit, boolean isBalanced, List<PostingFlowInventoryLine> inventoryMovements,
                                   PostingFlowGstImpact gstImpact, BigDecimal totalTaxableRevenue, BigDecimal totalInventoryCost,
                                   BigDecimal totalOrderProfit, BigDecimal orderProfitMarginPercent) {
            this(documentType, documentNo, documentDate, partyName, partyGstin, totalAmount, postingProfile, summaryDescription,
                 journalVoucherNo, journalLines, totalDebit, totalCredit, isBalanced, inventoryMovements, gstImpact,
                 totalTaxableRevenue, totalInventoryCost, totalOrderProfit, orderProfitMarginPercent,
                 BigDecimal.ZERO, null, "SYSTEM", "AUTO_POST");
        }

        public PostingFlowResponse(String documentType, String documentNo, LocalDate documentDate, String partyName,
                                   String partyGstin, BigDecimal totalAmount, String postingProfile, String summaryDescription,
                                   String journalVoucherNo, List<PostingFlowVoucherLine> journalLines, BigDecimal totalDebit,
                                   BigDecimal totalCredit, boolean isBalanced, List<PostingFlowInventoryLine> inventoryMovements,
                                   PostingFlowGstImpact gstImpact) {
            this(documentType, documentNo, documentDate, partyName, partyGstin, totalAmount, postingProfile, summaryDescription,
                 journalVoucherNo, journalLines, totalDebit, totalCredit, isBalanced, inventoryMovements, gstImpact,
                 BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                 BigDecimal.ZERO, null, "SYSTEM", "AUTO_POST");
        }
    }
}
