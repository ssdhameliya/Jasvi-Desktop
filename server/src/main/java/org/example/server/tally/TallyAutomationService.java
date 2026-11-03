package org.example.server.tally;

import org.example.server.persistence.entity.*;
import org.example.server.persistence.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class TallyAutomationService {
    private static final Logger log = LoggerFactory.getLogger(TallyAutomationService.class);

    private final ClientProfileRepository clientRepo;
    private final UploadBatchRepository batchRepo;
    private final BillRecordRepository billRepo;
    private final BillLineItemRepository lineRepo;
    private final TallyMappingCacheRepository cacheRepo;
    private final HttpClient httpClient;

    public TallyAutomationService(
            ClientProfileRepository clientRepo,
            UploadBatchRepository batchRepo,
            BillRecordRepository billRepo,
            BillLineItemRepository lineRepo,
            TallyMappingCacheRepository cacheRepo) {
        this.clientRepo = clientRepo;
        this.batchRepo = batchRepo;
        this.billRepo = billRepo;
        this.lineRepo = lineRepo;
        this.cacheRepo = cacheRepo;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /* =========================================================================
       1. CLIENT PROFILES
       ========================================================================= */

    @Transactional(readOnly = true)
    public List<TallyAutomationDtos.ClientProfileDto> listProfiles() {
        return clientRepo.findAllByOrderByClientNameAsc().stream()
                .map(this::toProfileDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public TallyAutomationDtos.ClientProfileDto saveProfile(TallyAutomationDtos.ClientProfileDto dto) {
        ClientProfileEntity entity;
        if (dto.id() != null && dto.id() > 0) {
            entity = clientRepo.findById(dto.id())
                    .orElseThrow(() -> new IllegalArgumentException("Client profile not found: " + dto.id()));
        } else {
            entity = new ClientProfileEntity();
        }

        entity.setClientName(dto.clientName() != null ? dto.clientName().trim() : "Default Client");
        entity.setTallyCompanyName(dto.tallyCompanyName() != null ? dto.tallyCompanyName().trim() : entity.getClientName());
        entity.setGstin(dto.gstin() != null ? dto.gstin().trim().toUpperCase(Locale.ROOT) : null);
        entity.setStateCode(dto.stateCode() != null ? dto.stateCode().trim() : "24");
        entity.setDefaultMode(dto.defaultMode() != null ? dto.defaultMode() : "SUMMARY");
        entity.setDefaultPurchaseLedger(dto.defaultPurchaseLedger() != null ? dto.defaultPurchaseLedger().trim() : "Purchase Account");
        entity.setDefaultRoundoffLedger(dto.defaultRoundoffLedger() != null ? dto.defaultRoundoffLedger().trim() : "Round Off");
        entity.setShowGstColumns(dto.showGstColumns());
        entity.setShowHsnColumn(dto.showHsnColumn());
        entity.setShowCostCenter(dto.showCostCenter());
        entity.setShowPoReference(dto.showPoReference());
        entity.setExcelColumnMappingJson(dto.excelColumnMappingJson());

        entity = clientRepo.save(entity);
        return toProfileDto(entity);
    }

    @Transactional
    public void deleteProfile(Long id) {
        clientRepo.deleteById(id);
    }

    /* =========================================================================
       2. UPLOAD BATCHES
       ========================================================================= */

    @Transactional
    public TallyAutomationDtos.UploadBatchDto createBatch(Long clientProfileId, String reference, int totalFiles, String createdBy) {
        ClientProfileEntity client = null;
        if (clientProfileId != null) {
            client = clientRepo.findById(clientProfileId).orElse(null);
        }

        UploadBatchEntity batch = new UploadBatchEntity();
        batch.setClientProfileId(client != null ? client.getId() : clientProfileId);
        batch.setBatchReference(reference != null && !reference.isBlank()
                ? reference
                : "BATCH-" + System.currentTimeMillis());
        batch.setTotalFiles(Math.max(1, totalFiles));
        batch.setProcessedCount(0);
        batch.setApiCallsConsumed(0);
        batch.setStatus("PENDING");
        batch.setCreatedBy(createdBy != null ? createdBy : "User");

        batch = batchRepo.save(batch);
        return toBatchDto(batch);
    }

    @Transactional(readOnly = true)
    public Page<TallyAutomationDtos.UploadBatchDto> listBatches(Pageable pageable) {
        return batchRepo.findAllByOrderByCreatedAtDesc(pageable).map(this::toBatchDto);
    }

    @Transactional(readOnly = true)
    public TallyAutomationDtos.UploadBatchDto getBatch(Long batchId) {
        return batchRepo.findById(batchId)
                .map(this::toBatchDto)
                .orElseThrow(() -> new IllegalArgumentException("Batch not found: " + batchId));
    }

    /* =========================================================================
       3. BILL RECORDS & ACCURACY SCORING
       ========================================================================= */

    @Transactional(readOnly = true)
    public List<TallyAutomationDtos.BillRecordDto> listBills(Long batchId) {
        return billRepo.findByBatchIdOrderByIdAsc(batchId).stream()
                .map(this::toBillDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public TallyAutomationDtos.BillRecordDto saveBill(TallyAutomationDtos.BillRecordDto dto) {
        BillRecordEntity entity;
        if (dto.id() != null && dto.id() > 0) {
            entity = billRepo.findById(dto.id())
                    .orElseThrow(() -> new IllegalArgumentException("Bill record not found: " + dto.id()));
        } else {
            entity = new BillRecordEntity();
            entity.setBatchId(dto.batchId());
        }

        entity.setClientProfileId(dto.clientProfileId());

        entity.setVoucherMode(dto.voucherMode() != null ? dto.voucherMode() : "SUMMARY");
        entity.setInvoiceNumber(dto.invoiceNumber() != null ? dto.invoiceNumber().trim() : "");
        entity.setInvoiceDate(dto.invoiceDate() != null ? dto.invoiceDate() : LocalDate.now());
        entity.setVendorNamePrinted(dto.vendorNamePrinted() != null ? dto.vendorNamePrinted().trim() : "");
        entity.setVendorGstin(dto.vendorGstin() != null ? dto.vendorGstin().trim().toUpperCase(Locale.ROOT) : "");
        entity.setTallyPartyLedger(dto.tallyPartyLedger() != null ? dto.tallyPartyLedger().trim() : entity.getVendorNamePrinted());
        entity.setTallyPurchaseLedger(dto.tallyPurchaseLedger() != null ? dto.tallyPurchaseLedger().trim() : "Purchase Account");
        entity.setCostCenter(dto.costCenter());
        entity.setPoReference(dto.poReference());

        entity.setTaxableAmount(dto.taxableAmount() != null ? dto.taxableAmount() : BigDecimal.ZERO);
        entity.setCgstAmount(dto.cgstAmount() != null ? dto.cgstAmount() : BigDecimal.ZERO);
        entity.setSgstAmount(dto.sgstAmount() != null ? dto.sgstAmount() : BigDecimal.ZERO);
        entity.setIgstAmount(dto.igstAmount() != null ? dto.igstAmount() : BigDecimal.ZERO);
        entity.setRoundOff(dto.roundOff() != null ? dto.roundOff() : BigDecimal.ZERO);
        entity.setGrandTotal(dto.grandTotal() != null ? dto.grandTotal() : BigDecimal.ZERO);

        entity.setAttachedFilePaths(dto.attachedFilePaths());
        entity.setStatus(dto.status() != null ? dto.status() : "EXTRACTED");
        entity.setTallyVoucherGuid(dto.tallyVoucherGuid());

        // Line Items if ITEM mode
        List<BillLineItemEntity> lineEntities = new ArrayList<>();
        if ("ITEM".equalsIgnoreCase(entity.getVoucherMode()) && dto.lineItems() != null) {
            int lineNum = 1;
            for (var itemDto : dto.lineItems()) {
                BillLineItemEntity line = new BillLineItemEntity();
                line.setBillRecordId(entity.getId());
                line.setLineNumber(lineNum++);
                line.setExtractedItemName(itemDto.extractedItemName() != null ? itemDto.extractedItemName().trim() : "Item " + lineNum);
                line.setMappedTallyStockItem(itemDto.mappedTallyStockItem() != null ? itemDto.mappedTallyStockItem().trim() : line.getExtractedItemName());
                line.setHsnSac(itemDto.hsnSac());
                line.setQuantity(itemDto.quantity() != null ? itemDto.quantity() : BigDecimal.ONE);
                line.setUom(itemDto.uom() != null ? itemDto.uom() : "NOS");
                line.setRate(itemDto.rate() != null ? itemDto.rate() : BigDecimal.ZERO);
                line.setDiscountAmount(itemDto.discountAmount() != null ? itemDto.discountAmount() : BigDecimal.ZERO);
                line.setTaxRate(itemDto.taxRate() != null ? itemDto.taxRate() : BigDecimal.ZERO);
                line.setTaxableAmount(itemDto.taxableAmount() != null ? itemDto.taxableAmount() : BigDecimal.ZERO);

                // Check line math
                BigDecimal expected = line.getQuantity().multiply(line.getRate()).subtract(line.getDiscountAmount()).setScale(2, RoundingMode.HALF_UP);
                if (expected.compareTo(line.getTaxableAmount()) == 0) {
                    line.setLineAccuracyPercentage(new BigDecimal("100.00"));
                    line.setLineErrorNotes(null);
                } else {
                    line.setLineAccuracyPercentage(new BigDecimal("60.00"));
                    line.setLineErrorNotes("Line math mismatch: Qty x Rate - Disc = " + expected + ", Extracted = " + line.getTaxableAmount());
                }
                lineEntities.add(line);
            }
        }

        // Validate Accuracy
        AccuracyAudit audit = calculateAccuracy(entity, lineEntities);
        entity.setAccuracyPercentage(audit.score);
        entity.setValidationNotes(audit.notes);

        entity = billRepo.save(entity);

        // Save child line items
        lineRepo.deleteByBillRecordId(entity.getId());
        if (!lineEntities.isEmpty()) {
            for (var l : lineEntities) {
                l.setBillRecordId(entity.getId());
            }
            lineRepo.saveAll(lineEntities);
        }

        // Auto-learning mapping cache update
        learnMappings(entity, lineEntities);

        return toBillDto(entity);
    }

    @Transactional
    public void deleteBill(Long id) {
        billRepo.deleteById(id);
    }

    @Transactional(readOnly = true)
    public TallyAutomationDtos.BatchSummaryMetrics computeMetrics(Long batchId) {
        List<BillRecordEntity> bills = billRepo.findByBatchIdOrderByIdAsc(batchId);
        int total = bills.size();
        int high = 0;
        int review = 0;
        int exported = 0;
        BigDecimal taxable = BigDecimal.ZERO;
        BigDecimal grand = BigDecimal.ZERO;

        for (var b : bills) {
            if ("EXPORTED_TALLY".equalsIgnoreCase(b.getStatus())) {
                exported++;
            }
            if (b.getAccuracyPercentage() != null && b.getAccuracyPercentage().compareTo(new BigDecimal("95.00")) >= 0) {
                high++;
            } else {
                review++;
            }
            taxable = taxable.add(b.getTaxableAmount());
            grand = grand.add(b.getGrandTotal());
        }

        return new TallyAutomationDtos.BatchSummaryMetrics(total, high, review, exported, taxable, grand);
    }

    /* =========================================================================
       4. ACCURACY SCORING & AUDIT ENGINE
       ========================================================================= */

    private record AccuracyAudit(BigDecimal score, String notes) {}

    private AccuracyAudit calculateAccuracy(BillRecordEntity bill, List<BillLineItemEntity> lines) {
        int penalty = 0;
        List<String> issues = new ArrayList<>();

        if (bill.getInvoiceNumber() == null || bill.getInvoiceNumber().isBlank()) {
            penalty += 20;
            issues.add("Missing Invoice Number");
        }
        if (bill.getInvoiceDate() == null) {
            penalty += 10;
            issues.add("Missing Invoice Date");
        }
        if (bill.getVendorNamePrinted() == null || bill.getVendorNamePrinted().isBlank()) {
            penalty += 20;
            issues.add("Missing Vendor Name");
        }

        // GSTIN format check (15 alphanum)
        if (bill.getVendorGstin() != null && !bill.getVendorGstin().isBlank()) {
            String g = bill.getVendorGstin().trim();
            if (g.length() != 15) {
                penalty += 15;
                issues.add("Vendor GSTIN length is not 15 characters (" + g.length() + ")");
            }
        }

        // Vertical math check: Taxable + CGST + SGST + IGST + RoundOff == GrandTotal
        BigDecimal calculatedTotal = bill.getTaxableAmount()
                .add(bill.getCgstAmount())
                .add(bill.getSgstAmount())
                .add(bill.getIgstAmount())
                .add(bill.getRoundOff())
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal diff = calculatedTotal.subtract(bill.getGrandTotal()).abs();
        if (diff.compareTo(new BigDecimal("0.05")) > 0) {
            penalty += 25;
            issues.add("Vertical tax balance mismatch: Calculated sum (" + calculatedTotal + ") != Grand Total (" + bill.getGrandTotal() + "), Diff: " + diff);
        }

        // If line items exist, check item sum vs bill taxable
        if (!lines.isEmpty()) {
            BigDecimal sumLines = lines.stream()
                    .map(BillLineItemEntity::getTaxableAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal lineDiff = sumLines.subtract(bill.getTaxableAmount()).abs();
            if (lineDiff.compareTo(new BigDecimal("0.05")) > 0) {
                penalty += 20;
                issues.add("Line items total (" + sumLines + ") != Header taxable (" + bill.getTaxableAmount() + ")");
            }
        }

        int scoreInt = Math.max(0, 100 - penalty);
        BigDecimal score = new BigDecimal(scoreInt).setScale(2, RoundingMode.HALF_UP);
        String notes = issues.isEmpty() ? "100% Mathematically Balanced & Statutory Compliant." : String.join("; ", issues);
        return new AccuracyAudit(score, notes);
    }

    /* =========================================================================
       5. AUTO-LEARNING MAPPINGS
       ========================================================================= */

    private void learnMappings(BillRecordEntity bill, List<BillLineItemEntity> lines) {
        if (bill.getClientProfileId() == null) return;
        Long clientId = bill.getClientProfileId();

        // Vendor to Ledger
        if (bill.getVendorNamePrinted() != null && !bill.getVendorNamePrinted().isBlank()
                && bill.getTallyPartyLedger() != null && !bill.getTallyPartyLedger().isBlank()) {
            saveOrIncrementMapping(clientId, "VENDOR_TO_LEDGER", bill.getVendorNamePrinted().trim(),
                    bill.getTallyPartyLedger().trim(), bill.getTallyPurchaseLedger());
        }

        // Item to Stock Item
        for (var l : lines) {
            if (l.getExtractedItemName() != null && !l.getExtractedItemName().isBlank()
                    && l.getMappedTallyStockItem() != null && !l.getMappedTallyStockItem().isBlank()) {
                saveOrIncrementMapping(clientId, "ITEM_TO_STOCK", l.getExtractedItemName().trim(),
                        l.getMappedTallyStockItem().trim(), l.getUom());
            }
        }
    }

    private void saveOrIncrementMapping(Long clientId, String type, String source, String target, String secondary) {
        try {
            var existing = cacheRepo.findByClientProfileIdAndMappingTypeAndSourceIdentifier(clientId, type, source);
            if (existing.isPresent()) {
                var e = existing.get();
                e.setTallyTargetName(target);
                if (secondary != null && !secondary.isBlank()) e.setTallySecondaryName(secondary);
                e.setConfidenceHits(e.getConfidenceHits() + 1);
                e.setLastAppliedAt(OffsetDateTime.now());
                cacheRepo.save(e);
            } else {
                ClientProfileEntity client = clientRepo.findById(clientId).orElse(null);
                if (client != null) {
                    TallyMappingCacheEntity mapping = new TallyMappingCacheEntity();
                    mapping.setClientProfile(client);
                    mapping.setMappingType(type);
                    mapping.setSourceIdentifier(source);
                    mapping.setTallyTargetName(target);
                    mapping.setTallySecondaryName(secondary);
                    mapping.setConfidenceHits(1);
                    mapping.setLastAppliedAt(OffsetDateTime.now());
                    cacheRepo.save(mapping);
                }
            }
        } catch (Exception ex) {
            log.warn("Could not cache mapping: {}", ex.getMessage());
        }
    }

    /* =========================================================================
       6. TALLYPRIME PUSH & EXPORT ENGINE
       ========================================================================= */

    @Transactional
    public TallyAutomationDtos.TallyExportResponse exportToTally(TallyAutomationDtos.TallyExportRequest request) {
        List<BillRecordEntity> bills = billRepo.findByBatchIdOrderByIdAsc(request.batchId());
        if (request.billIds() != null && !request.billIds().isEmpty()) {
            Set<Long> filter = new HashSet<>(request.billIds());
            bills = bills.stream().filter(b -> filter.contains(b.getId())).collect(Collectors.toList());
        }

        if (bills.isEmpty()) {
            return new TallyAutomationDtos.TallyExportResponse(false, 0, 0, "No bills selected for export.", "");
        }

        String format = request.exportFormat() != null ? request.exportFormat().toUpperCase(Locale.ROOT) : "HTTP";
        String tallyXml = generateTallyXml(bills);

        if ("HTTP".equals(format)) {
            String url = request.tallyUrl() != null && !request.tallyUrl().isBlank()
                    ? request.tallyUrl().trim()
                    : "http://localhost:9000";
            try {
                HttpRequest httpReq = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/xml; charset=UTF-8")
                        .timeout(Duration.ofSeconds(15))
                        .POST(HttpRequest.BodyPublishers.ofString(tallyXml))
                        .build();

                HttpResponse<String> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200 && !resp.body().toLowerCase(Locale.ROOT).contains("<lineerror>")) {
                    for (var b : bills) {
                        b.setStatus("EXPORTED_TALLY");
                        b.setTallyVoucherGuid("TALLY-VCH-" + b.getInvoiceNumber());
                    }
                    billRepo.saveAll(bills);
                    return new TallyAutomationDtos.TallyExportResponse(true, bills.size(), 0,
                            "Successfully pushed " + bills.size() + " vouchers to TallyPrime at " + url, resp.body());
                } else {
                    return new TallyAutomationDtos.TallyExportResponse(false, 0, bills.size(),
                            "TallyPrime returned error (HTTP " + resp.statusCode() + "): " + resp.body(), resp.body());
                }
            } catch (Exception e) {
                return new TallyAutomationDtos.TallyExportResponse(false, 0, bills.size(),
                        "Could not connect to TallyPrime at " + url + ": " + e.getMessage(), tallyXml);
            }
        } else if ("JSON".equals(format)) {
            String jsonPayload = generateTallyJson(bills);
            return new TallyAutomationDtos.TallyExportResponse(true, bills.size(), 0,
                    "Generated TallyPrime 7.0 JSON for " + bills.size() + " vouchers.", jsonPayload);
        } else if ("GST_PORTAL".equals(format) || "GST".equals(format)) {
            String gstJson = generateGstPortalJson(bills);
            return new TallyAutomationDtos.TallyExportResponse(true, bills.size(), 0,
                    "Generated GST Portal B2B JSON for " + bills.size() + " vouchers.", gstJson);
        } else {
            // Excel / XML fallback
            return new TallyAutomationDtos.TallyExportResponse(true, bills.size(), 0,
                    "Generated TallyPrime XML for " + bills.size() + " vouchers.", tallyXml);
        }
    }

    private String generateTallyXml(List<BillRecordEntity> bills) {
        StringBuilder sb = new StringBuilder();
        sb.append("<ENVELOPE>\n");
        sb.append("  <HEADER>\n");
        sb.append("    <TALLYREQUEST>Import Data</TALLYREQUEST>\n");
        sb.append("  </HEADER>\n");
        sb.append("  <BODY>\n");
        sb.append("    <IMPORTDATA>\n");
        sb.append("      <REQUESTDESC>\n");
        sb.append("        <REPORTNAME>Vouchers</REPORTNAME>\n");
        sb.append("      </REQUESTDESC>\n");
        sb.append("      <REQUESTDATA>\n");

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyyMMdd");
        for (var b : bills) {
            ClientProfileEntity profile = b.getClientProfileId() != null ? clientRepo.findById(b.getClientProfileId()).orElse(null) : null;
            String company = profile != null ? profile.getTallyCompanyName() : "Primary Company";
            String dateStr = b.getInvoiceDate() != null ? b.getInvoiceDate().format(dtf) : LocalDate.now().format(dtf);
            sb.append("        <TALLYMESSAGE xmlns:UDF=\"TallyUDF\">\n");
            sb.append("          <VOUCHER VCHTYPE=\"Purchase\" ACTION=\"Create\">\n");
            sb.append("            <DATE>").append(dateStr).append("</DATE>\n");
            sb.append("            <EFFECTIVEDATE>").append(dateStr).append("</EFFECTIVEDATE>\n");
            sb.append("            <VOUCHERTYPENAME>Purchase</VOUCHERTYPENAME>\n");
            sb.append("            <REFERENCE>").append(escapeXml(b.getInvoiceNumber())).append("</REFERENCE>\n");
            sb.append("            <PARTYLEDGERNAME>").append(escapeXml(b.getTallyPartyLedger())).append("</PARTYLEDGERNAME>\n");

            // Party Credit Ledger
            sb.append("            <ALLLEDGERENTRIES.LIST>\n");
            sb.append("              <LEDGERNAME>").append(escapeXml(b.getTallyPartyLedger())).append("</LEDGERNAME>\n");
            sb.append("              <ISDEEMEDPOSITIVE>No</ISDEEMEDPOSITIVE>\n");
            sb.append("              <AMOUNT>").append(b.getGrandTotal()).append("</AMOUNT>\n");
            sb.append("            </ALLLEDGERENTRIES.LIST>\n");

            // Purchase Debit Ledger
            sb.append("            <ALLLEDGERENTRIES.LIST>\n");
            sb.append("              <LEDGERNAME>").append(escapeXml(b.getTallyPurchaseLedger())).append("</LEDGERNAME>\n");
            sb.append("              <ISDEEMEDPOSITIVE>Yes</ISDEEMEDPOSITIVE>\n");
            sb.append("              <AMOUNT>-").append(b.getTaxableAmount()).append("</AMOUNT>\n");
            sb.append("            </ALLLEDGERENTRIES.LIST>\n");

            // CGST Ledger
            if (b.getCgstAmount().compareTo(BigDecimal.ZERO) > 0) {
                sb.append("            <ALLLEDGERENTRIES.LIST>\n");
                sb.append("              <LEDGERNAME>Input CGST</LEDGERNAME>\n");
                sb.append("              <ISDEEMEDPOSITIVE>Yes</ISDEEMEDPOSITIVE>\n");
                sb.append("              <AMOUNT>-").append(b.getCgstAmount()).append("</AMOUNT>\n");
                sb.append("            </ALLLEDGERENTRIES.LIST>\n");
            }

            // SGST Ledger
            if (b.getSgstAmount().compareTo(BigDecimal.ZERO) > 0) {
                sb.append("            <ALLLEDGERENTRIES.LIST>\n");
                sb.append("              <LEDGERNAME>Input SGST</LEDGERNAME>\n");
                sb.append("              <ISDEEMEDPOSITIVE>Yes</ISDEEMEDPOSITIVE>\n");
                sb.append("              <AMOUNT>-").append(b.getSgstAmount()).append("</AMOUNT>\n");
                sb.append("            </ALLLEDGERENTRIES.LIST>\n");
            }

            // IGST Ledger
            if (b.getIgstAmount().compareTo(BigDecimal.ZERO) > 0) {
                sb.append("            <ALLLEDGERENTRIES.LIST>\n");
                sb.append("              <LEDGERNAME>Input IGST</LEDGERNAME>\n");
                sb.append("              <ISDEEMEDPOSITIVE>Yes</ISDEEMEDPOSITIVE>\n");
                sb.append("              <AMOUNT>-").append(b.getIgstAmount()).append("</AMOUNT>\n");
                sb.append("            </ALLLEDGERENTRIES.LIST>\n");
            }

            // Round Off
            if (b.getRoundOff().compareTo(BigDecimal.ZERO) != 0) {
                String roLedger = profile != null ? profile.getDefaultRoundoffLedger() : "Round Off";
                boolean positive = b.getRoundOff().compareTo(BigDecimal.ZERO) > 0;
                sb.append("            <ALLLEDGERENTRIES.LIST>\n");
                sb.append("              <LEDGERNAME>").append(escapeXml(roLedger)).append("</LEDGERNAME>\n");
                sb.append("              <ISDEEMEDPOSITIVE>").append(positive ? "No" : "Yes").append("</ISDEEMEDPOSITIVE>\n");
                sb.append("              <AMOUNT>").append(b.getRoundOff().abs().toPlainString()).append("</AMOUNT>\n");
                sb.append("            </ALLLEDGERENTRIES.LIST>\n");
            }

            sb.append("          </VOUCHER>\n");
            sb.append("        </TALLYMESSAGE>\n");
        }

        sb.append("      </REQUESTDATA>\n");
        sb.append("    </IMPORTDATA>\n");
        sb.append("  </BODY>\n");
        sb.append("</ENVELOPE>\n");
        return sb.toString();
    }

    private String generateTallyJson(List<BillRecordEntity> bills) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"vouchers\": [\n");
        for (int i = 0; i < bills.size(); i++) {
            var b = bills.get(i);
            sb.append("    {\n");
            sb.append("      \"voucherType\": \"Purchase\",\n");
            sb.append("      \"invoiceNumber\": \"").append(escapeJson(b.getInvoiceNumber())).append("\",\n");
            sb.append("      \"invoiceDate\": \"").append(b.getInvoiceDate()).append("\",\n");
            sb.append("      \"partyLedger\": \"").append(escapeJson(b.getTallyPartyLedger())).append("\",\n");
            sb.append("      \"vendorGstin\": \"").append(escapeJson(b.getVendorGstin())).append("\",\n");
            sb.append("      \"taxableAmount\": ").append(b.getTaxableAmount()).append(",\n");
            sb.append("      \"cgstAmount\": ").append(b.getCgstAmount()).append(",\n");
            sb.append("      \"sgstAmount\": ").append(b.getSgstAmount()).append(",\n");
            sb.append("      \"igstAmount\": ").append(b.getIgstAmount()).append(",\n");
            sb.append("      \"roundOff\": ").append(b.getRoundOff()).append(",\n");
            sb.append("      \"grandTotal\": ").append(b.getGrandTotal()).append("\n");
            sb.append("    }").append(i < bills.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ]\n}");
        return sb.toString();
    }


    private String generateGstPortalJson(List<BillRecordEntity> bills) {
        StringBuilder sb = new StringBuilder();
        String buyerGstin = "24AABCA5514E1Z1";
        if (!bills.isEmpty() && bills.get(0).getClientProfileId() != null) {
            ClientProfileEntity p = clientRepo.findById(bills.get(0).getClientProfileId()).orElse(null);
            if (p != null && p.getGstin() != null && !p.getGstin().isBlank()) {
                buyerGstin = p.getGstin().trim();
            }
        }
        String fp = LocalDate.now().format(DateTimeFormatter.ofPattern("MMyyyy"));
        if (!bills.isEmpty() && bills.get(0).getInvoiceDate() != null) {
            fp = bills.get(0).getInvoiceDate().format(DateTimeFormatter.ofPattern("MMyyyy"));
        }

        Map<String, List<BillRecordEntity>> byVendor = new LinkedHashMap<>();
        for (var b : bills) {
            String ctin = (b.getVendorGstin() != null && !b.getVendorGstin().isBlank())
                    ? b.getVendorGstin().trim().toUpperCase(Locale.ROOT)
                    : "URP";
            byVendor.computeIfAbsent(ctin, k -> new ArrayList<>()).add(b);
        }

        sb.append("{\n");
        sb.append("  \"gstin\": \"").append(escapeJson(buyerGstin)).append("\",\n");
        sb.append("  \"fp\": \"").append(fp).append("\",\n");
        sb.append("  \"version\": \"GST3.0.4\",\n");
        sb.append("  \"hash\": \"hash\",\n");
        sb.append("  \"b2b\": [\n");

        int vIndex = 0;
        for (var entry : byVendor.entrySet()) {
            String ctin = entry.getKey();
            List<BillRecordEntity> vendorBills = entry.getValue();
            sb.append("    {\n");
            sb.append("      \"ctin\": \"").append(escapeJson(ctin)).append("\",\n");
            sb.append("      \"inv\": [\n");

            for (int i = 0; i < vendorBills.size(); i++) {
                var b = vendorBills.get(i);
                String idt = b.getInvoiceDate() != null ? b.getInvoiceDate().format(DateTimeFormatter.ofPattern("dd-MM-yyyy")) : LocalDate.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
                String pos = (ctin.length() >= 2 && Character.isDigit(ctin.charAt(0)) && Character.isDigit(ctin.charAt(1)))
                        ? ctin.substring(0, 2)
                        : "24";

                BigDecimal taxVal = b.getTaxableAmount() != null ? b.getTaxableAmount() : BigDecimal.ZERO;
                BigDecimal cgst = b.getCgstAmount() != null ? b.getCgstAmount() : BigDecimal.ZERO;
                BigDecimal sgst = b.getSgstAmount() != null ? b.getSgstAmount() : BigDecimal.ZERO;
                BigDecimal igst = b.getIgstAmount() != null ? b.getIgstAmount() : BigDecimal.ZERO;
                BigDecimal totalTax = cgst.add(sgst).add(igst);
                double rate = 18.0;
                if (taxVal.compareTo(BigDecimal.ZERO) > 0 && totalTax.compareTo(BigDecimal.ZERO) > 0) {
                    rate = totalTax.multiply(BigDecimal.valueOf(100)).divide(taxVal, 1, RoundingMode.HALF_UP).doubleValue();
                }

                sb.append("        {\n");
                sb.append("          \"inum\": \"").append(escapeJson(b.getInvoiceNumber() != null ? b.getInvoiceNumber() : "INV-1")).append("\",\n");
                sb.append("          \"idt\": \"").append(idt).append("\",\n");
                sb.append("          \"val\": ").append(b.getGrandTotal() != null ? b.getGrandTotal() : BigDecimal.ZERO).append(",\n");
                sb.append("          \"pos\": \"").append(pos).append("\",\n");
                sb.append("          \"rchrg\": \"N\",\n");
                sb.append("          \"inv_typ\": \"R\",\n");
                sb.append("          \"itms\": [\n");
                sb.append("            {\n");
                sb.append("              \"num\": 1,\n");
                sb.append("              \"itm_det\": {\n");
                sb.append("                \"rt\": ").append(rate).append(",\n");
                sb.append("                \"txval\": ").append(taxVal).append(",\n");
                sb.append("                \"camt\": ").append(cgst).append(",\n");
                sb.append("                \"samt\": ").append(sgst).append(",\n");
                sb.append("                \"csamt\": 0.00\n");
                sb.append("              }\n");
                sb.append("            }\n");
                sb.append("          ]\n");
                sb.append("        }").append(i < vendorBills.size() - 1 ? "," : "").append("\n");
            }
            sb.append("      ]\n");
            sb.append("    }").append(vIndex < byVendor.size() - 1 ? "," : "").append("\n");
            vIndex++;
        }
        sb.append("  ]\n}");
        return sb.toString();
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    /* =========================================================================
       MAPPERS
       ========================================================================= */

    private TallyAutomationDtos.ClientProfileDto toProfileDto(ClientProfileEntity e) {
        return new TallyAutomationDtos.ClientProfileDto(
                e.getId(),
                e.getClientName(),
                e.getTallyCompanyName(),
                e.getGstin(),
                e.getStateCode(),
                e.getDefaultMode(),
                e.getDefaultPurchaseLedger(),
                e.getDefaultRoundoffLedger(),
                Boolean.TRUE.equals(e.getShowGstColumns()),
                Boolean.TRUE.equals(e.getShowHsnColumn()),
                Boolean.TRUE.equals(e.getShowCostCenter()),
                Boolean.TRUE.equals(e.getShowPoReference()),
                e.getExcelColumnMappingJson()
        );
    }

    private TallyAutomationDtos.UploadBatchDto toBatchDto(UploadBatchEntity e) {
        return new TallyAutomationDtos.UploadBatchDto(
                e.getId(),
                e.getClientProfileId(),
                e.getClientProfileId() != null ? clientRepo.findById(e.getClientProfileId()).map(ClientProfileEntity::getClientName).orElse("General") : "General",
                e.getBatchReference(),
                e.getTotalFiles(),
                e.getProcessedCount(),
                e.getApiCallsConsumed(),
                e.getStatus(),
                e.getCreatedBy(),
                e.getCreatedAt()
        );
    }

    private TallyAutomationDtos.BillRecordDto toBillDto(BillRecordEntity e) {
        List<TallyAutomationDtos.BillLineItemDto> lines = lineRepo.findByBillRecordIdOrderByLineNumberAsc(e.getId())
                .stream().map(this::toLineDto).collect(Collectors.toList());

        return new TallyAutomationDtos.BillRecordDto(
                e.getId(),
                e.getBatchId(),
                e.getClientProfileId(),
                e.getVoucherMode(),
                e.getInvoiceNumber(),
                e.getInvoiceDate(),
                e.getVendorNamePrinted(),
                e.getVendorGstin(),
                e.getTallyPartyLedger(),
                e.getTallyPurchaseLedger(),
                e.getCostCenter(),
                e.getPoReference(),
                e.getTaxableAmount(),
                e.getCgstAmount(),
                e.getSgstAmount(),
                e.getIgstAmount(),
                e.getRoundOff(),
                e.getGrandTotal(),
                e.getAccuracyPercentage(),
                e.getValidationNotes(),
                e.getAttachedFilePaths(),
                e.getStatus(),
                e.getTallyVoucherGuid(),
                lines
        );
    }

    private TallyAutomationDtos.BillLineItemDto toLineDto(BillLineItemEntity l) {
        return new TallyAutomationDtos.BillLineItemDto(
                l.getId(),
                l.getBillRecordId(),
                l.getLineNumber(),
                l.getExtractedItemName(),
                l.getMappedTallyStockItem(),
                l.getHsnSac(),
                l.getQuantity(),
                l.getUom(),
                l.getRate(),
                l.getDiscountAmount(),
                l.getTaxRate(),
                l.getTaxableAmount(),
                l.getLineAccuracyPercentage(),
                l.getLineErrorNotes()
        );
    }
}
