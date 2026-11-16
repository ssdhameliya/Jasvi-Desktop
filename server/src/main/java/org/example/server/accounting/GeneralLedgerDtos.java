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
            BigDecimal totalValuation
    ) {}

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
            PostingFlowGstImpact gstImpact
    ) {}
}
