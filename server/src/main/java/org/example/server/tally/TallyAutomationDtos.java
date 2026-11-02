package org.example.server.tally;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class TallyAutomationDtos {
    private TallyAutomationDtos() {}

    public record ClientProfileDto(
        Long id,
        String clientName,
        String tallyCompanyName,
        String gstin,
        String stateCode,
        String defaultMode,
        String defaultPurchaseLedger,
        String defaultRoundoffLedger,
        boolean showGstColumns,
        boolean showHsnColumn,
        boolean showCostCenter,
        boolean showPoReference,
        String excelColumnMappingJson
    ) {}

    public record UploadBatchDto(
        Long id,
        Long clientProfileId,
        String clientName,
        String batchReference,
        int totalFiles,
        int processedCount,
        int apiCallsConsumed,
        String status,
        String createdBy,
        OffsetDateTime createdAt
    ) {}

    public record BillLineItemDto(
        Long id,
        Long billRecordId,
        int lineNumber,
        String extractedItemName,
        String mappedTallyStockItem,
        String hsnSac,
        BigDecimal quantity,
        String uom,
        BigDecimal rate,
        BigDecimal discountAmount,
        BigDecimal taxRate,
        BigDecimal taxableAmount,
        BigDecimal lineAccuracyPercentage,
        String lineErrorNotes
    ) {}

    public record BillRecordDto(
        Long id,
        Long batchId,
        Long clientProfileId,
        String voucherMode,
        String invoiceNumber,
        LocalDate invoiceDate,
        String vendorNamePrinted,
        String vendorGstin,
        String tallyPartyLedger,
        String tallyPurchaseLedger,
        String costCenter,
        String poReference,
        BigDecimal taxableAmount,
        BigDecimal cgstAmount,
        BigDecimal sgstAmount,
        BigDecimal igstAmount,
        BigDecimal roundOff,
        BigDecimal grandTotal,
        BigDecimal accuracyPercentage,
        String validationNotes,
        String attachedFilePaths,
        String status,
        String tallyVoucherGuid,
        List<BillLineItemDto> lineItems
    ) {}

    public record BatchSummaryMetrics(
        int totalBills,
        int highAccuracyCount,
        int needsReviewCount,
        int exportedCount,
        BigDecimal totalTaxable,
        BigDecimal totalGrandTotal
    ) {}

    public record TallyExportRequest(
        Long batchId,
        List<Long> billIds,
        String exportFormat, // "HTTP", "JSON", "EXCEL"
        String tallyUrl
    ) {}

    public record TallyExportResponse(
        boolean success,
        int exportedCount,
        int failedCount,
        String message,
        String payloadContent
    ) {}

    public record SaveMappingRequest(
        Long clientProfileId,
        String mappingType,
        String sourceIdentifier,
        String targetName,
        String secondaryName
    ) {}
}
