package org.example.server.aging;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class AgingAnalysisDtos {
    private AgingAnalysisDtos() {}

    public record PartyAgingDto(
            Long partyId,
            String partyName,
            String partyType,
            String gstin,
            String phone,
            BigDecimal bucket0To30,
            BigDecimal bucket31To60,
            BigDecimal bucket61To90,
            BigDecimal bucket90Plus,
            BigDecimal totalDue,
            String riskLevel
    ) {}

    public record AgingSummaryDto(
            LocalDate asOfDate,
            String type,
            BigDecimal totalOutstanding,
            BigDecimal totalOverdue,
            int totalCount,
            List<PartyAgingDto> parties
    ) {}

    public record DesktopAgingRow(
            Long partyId,
            String partyName,
            String phone,
            String email,
            int unpaidInvoiceCount,
            BigDecimal totalOutstanding,
            BigDecimal bucket0To30,
            BigDecimal bucket31To60,
            BigDecimal bucket61To90,
            BigDecimal bucket90Plus,
            String riskLevel,
            String lastRemindedAt,
            String lastRemindedChannel
    ) {}

    public record DesktopAgingReport(
            String asOfDate,
            String reportType,
            int totalParties,
            BigDecimal grandTotal,
            BigDecimal grandBucket0To30,
            BigDecimal grandBucket31To60,
            BigDecimal grandBucket61To90,
            BigDecimal grandBucket90Plus,
            List<DesktopAgingRow> rows
    ) {}

    public record PartyStatementInvoiceDto(
            String invoiceNo,
            String invoiceDate,
            long daysOverdue,
            String bucketLabel,
            BigDecimal totalAmount,
            BigDecimal paidAmount,
            BigDecimal balanceDue
    ) {}

    public record PartyStatementDto(
            Long partyId,
            String partyName,
            String partyType,
            String gstin,
            String phone,
            String email,
            String address,
            String asOfDate,
            BigDecimal totalOutstanding,
            BigDecimal bucket0To30,
            BigDecimal bucket31To60,
            BigDecimal bucket61To90,
            BigDecimal bucket90Plus,
            List<PartyStatementInvoiceDto> invoices
    ) {}
}
