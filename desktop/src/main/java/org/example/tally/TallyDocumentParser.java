package org.example.tally;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.*;
import org.example.api.tally.TallyAutomationApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TallyDocumentParser {
    private static final Logger log = LoggerFactory.getLogger(TallyDocumentParser.class);

    private static final Pattern GSTIN_PATTERN = Pattern.compile("\\b(\\d{2}[A-Z]{5}\\d{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1})\\b");
    private static final Pattern INVOICE_NO_PATTERN = Pattern.compile("(?i)(?:Invoice\\s*(?:No|Number|#)?[\\s:]*|Bill\\s*(?:No|#)?[\\s:]*|Inv\\s*No[\\s:]*)([A-Za-z0-9\\-_/]{3,30})");
    private static final Pattern DATE_PATTERN = Pattern.compile("(?i)(?:Invoice\\s*Date|Bill\\s*Date|Date|Dated)[\\s:]*(\\d{1,2}[-/.\\s]\\d{1,2}[-/.\\s]\\d{2,4}|\\d{1,2}[-/.\\s][A-Za-z]{3}[-/.\\s]\\d{2,4})");
    private static final Pattern TOTAL_PATTERN = Pattern.compile("(?i)(?:Grand\\s*Total|Total\\s*Invoice\\s*Value|Invoice\\s*Total|Net\\s*Amount|Total\\s*Amount)[\\s:₹Rs.]*([\\d,]+\\.\\d{2}|\\b\\d+\\b)");
    private static final Pattern TAXABLE_PATTERN = Pattern.compile("(?i)(?:Taxable\\s*(?:Value|Amount)?|Sub\\s*Total)[\\s:₹Rs.]*([\\d,]+\\.\\d{2}|\\b\\d+\\b)");
    private static final Pattern CGST_PATTERN = Pattern.compile("(?i)(?:CGST)[\\s:₹Rs.]*([\\d,]+\\.\\d{2}|\\b\\d+\\b)");
    private static final Pattern SGST_PATTERN = Pattern.compile("(?i)(?:SGST|UTGST)[\\s:₹Rs.]*([\\d,]+\\.\\d{2}|\\b\\d+\\b)");
    private static final Pattern IGST_PATTERN = Pattern.compile("(?i)(?:IGST)[\\s:₹Rs.]*([\\d,]+\\.\\d{2}|\\b\\d+\\b)");

    // Table line pattern: Optional Sl, Item Description, HSN/SAC, Qty, UOM, (optional Rate), (optional Tax%), (optional Amount)
    private static final Pattern ITEM_ROW_PATTERN = Pattern.compile(
            "^\\s*(?:(\\d{1,3})\\s+)?([A-Za-z0-9\\s\\-()#&.,/]+?)\\s+(\\d{4,8})\\s+(\\d+(?:\\.\\d+)?)\\s+([A-Za-z]{2,5})(?:\\s+([\\d,]+\\.?\\d*))?(?:\\s+(\\d{1,2}(?:\\.\\d+)?%?))?(?:\\s+([\\d,]+\\.?\\d*))?\\s*$"
    );

    private TallyDocumentParser() {}

    public static TallyAutomationApiClient.BillRecordDto parseDocument(File file, Long batchId, Long clientProfileId, String defaultPartyLedger) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".pdf")) {
                return parsePdf(file, batchId, clientProfileId, defaultPartyLedger);
            } else if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) {
                return parseImage(file, batchId, clientProfileId, defaultPartyLedger);
            } else if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
                return parseExcel(file, batchId, clientProfileId, defaultPartyLedger);
            } else if (name.endsWith(".csv")) {
                return parseCsv(file, batchId, clientProfileId, defaultPartyLedger);
            }
        } catch (Exception ex) {
            log.warn("Failed deep parse of {}: {}", file.getName(), ex.getMessage());
        }
        return parseFallback(file, batchId, clientProfileId, defaultPartyLedger);
    }

    private static TallyAutomationApiClient.BillRecordDto parseImage(File file, Long batchId, Long clientProfileId, String defaultPartyLedger) {
        if (!WindowsOcrEngine.isAvailable()) {
            return parseFallback(file, batchId, clientProfileId, defaultPartyLedger);
        }
        List<String> lines = WindowsOcrEngine.extractTextLines(file);
        if (lines.isEmpty()) {
            return parseFallback(file, batchId, clientProfileId, defaultPartyLedger);
        }
        return parseFromTextLines(lines, file, batchId, clientProfileId, defaultPartyLedger);
    }

    private static TallyAutomationApiClient.BillRecordDto parsePdf(File file, Long batchId, Long clientProfileId, String defaultPartyLedger) {
        List<String> lines = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String rawText = stripper.getText(doc);
            if (rawText != null && !rawText.isBlank()) {
                for (String l : rawText.split("\\r?\\n")) {
                    l = l.trim();
                    if (!l.isBlank()) lines.add(l);
                }
            }

            // Scanned image PDF fallback: if digital text is empty or too sparse, use native OCR on rendered pages
            if (lines.size() < 4 && WindowsOcrEngine.isAvailable() && doc.getNumberOfPages() > 0) {
                org.apache.pdfbox.rendering.PDFRenderer renderer = new org.apache.pdfbox.rendering.PDFRenderer(doc);
                for (int p = 0; p < Math.min(2, doc.getNumberOfPages()); p++) {
                    java.awt.image.BufferedImage bim = renderer.renderImageWithDPI(p, 200);
                    lines.addAll(WindowsOcrEngine.extractTextLines(bim));
                }
            }
        } catch (Exception ex) {
            log.warn("PDF extraction warning for {}: {}", file.getName(), ex.getMessage());
        }

        if (lines.isEmpty()) {
            return parseFallback(file, batchId, clientProfileId, defaultPartyLedger);
        }
        return parseFromTextLines(lines, file, batchId, clientProfileId, defaultPartyLedger);
    }

    private static TallyAutomationApiClient.BillRecordDto parseFromTextLines(List<String> lines, File file, Long batchId, Long clientProfileId, String defaultPartyLedger) {
        String invNo = null;
        LocalDate invDate = null;
        String vendorName = null;
        String vendorGstin = null;
        BigDecimal taxable = null;
        BigDecimal cgst = BigDecimal.ZERO;
        BigDecimal sgst = BigDecimal.ZERO;
        BigDecimal igst = BigDecimal.ZERO;
        BigDecimal grandTotal = null;

        List<TallyAutomationApiClient.BillLineItemDto> items = new ArrayList<>();
        List<String> summaryLabels = new ArrayList<>();
        List<BigDecimal> summaryNumbers = new ArrayList<>();
        int itemIndex = 1;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isBlank()) continue;

            // 1. GSTIN detection (with OCR character stabilization: I -> 1 / Z)
            String sanitizedGstinCandidate = line.replaceAll("(?i)(?<=\\d{2}[A-Z]{5}\\d{4}[A-Z]{1}[0-9A-Z]{1})I(?=[0-9A-Z]{1})", "Z")
                    .replaceAll("(?i)(?<=\\d{2}[A-Z]{5}\\d{4}[A-Z]{1})I", "1");
            Matcher gstinMatcher = GSTIN_PATTERN.matcher(sanitizedGstinCandidate);
            if (gstinMatcher.find()) {
                String foundGstin = gstinMatcher.group(1).toUpperCase(Locale.ROOT);
                if (vendorGstin == null) {
                    vendorGstin = foundGstin;
                    if (vendorName == null && i > 0) {
                        for (int p = Math.max(0, i - 4); p < i; p++) {
                            String cand = lines.get(p).trim();
                            if (isProminentName(cand)) {
                                vendorName = (vendorName == null) ? cand : vendorName + " " + cand;
                            }
                        }
                    }
                }
            }

            // 2. Invoice No detection (supporting adjacent multi-line formats)
            if (invNo == null) {
                Matcher invMatcher = INVOICE_NO_PATTERN.matcher(line);
                if (invMatcher.find()) {
                    invNo = invMatcher.group(1).trim();
                } else if (line.matches("(?i)^(?:Invoice|Bill|Inv)\\s*(?:No|Number|#)?\\s*:?$")) {
                    for (int k = i + 1; k < Math.min(i + 4, lines.size()); k++) {
                        String next = lines.get(k).trim().replaceAll("^[:\\s]+", "");
                        if (next.matches("[A-Za-z0-9\\-_/]{3,30}")) {
                            invNo = next;
                            break;
                        }
                    }
                }
            }

            // 3. Date detection
            if (invDate == null) {
                Matcher dateMatcher = DATE_PATTERN.matcher(line);
                if (dateMatcher.find()) {
                    invDate = parseDate(dateMatcher.group(1).trim());
                }
            }

            // 4. Totals & Tax (Inline line matching)
            Matcher totalMatcher = TOTAL_PATTERN.matcher(line);
            if (totalMatcher.find() && grandTotal == null) {
                grandTotal = parseDecimal(totalMatcher.group(1));
            }

            Matcher taxMatcher = TAXABLE_PATTERN.matcher(line);
            if (taxMatcher.find() && taxable == null) {
                taxable = parseDecimal(taxMatcher.group(1));
            }

            Matcher cgstMatcher = CGST_PATTERN.matcher(line);
            if (cgstMatcher.find()) {
                cgst = parseDecimal(cgstMatcher.group(1));
            }

            Matcher sgstMatcher = SGST_PATTERN.matcher(line);
            if (sgstMatcher.find()) {
                sgst = parseDecimal(sgstMatcher.group(1));
            }

            Matcher igstMatcher = IGST_PATTERN.matcher(line);
            if (igstMatcher.find()) {
                igst = parseDecimal(igstMatcher.group(1));
            }

            // Summary block tracking
            if (line.matches("(?i).*(?:Combined\\s*Taxable|Taxable\\s*Value|Sub\\s*Total|Items\\s*Subtotal|CGST|SGST|IGST|Net\\s*Payable|Grand\\s*Total|Total\\s*Invoice\\s*Value).*")) {
                summaryLabels.add(line);
            }
            if (line.matches("^(?:Rs\\.?\\s*)?[\\d,]+(?:\\.[0-9Ool\\s]{1,2})?$") || line.matches("^[\\d,]+\\.\\d{2}$")) {
                BigDecimal d = parseDecimal(line);
                if (d.compareTo(BigDecimal.ZERO) > 0) {
                    summaryNumbers.add(d);
                }
            }

            // 5. Line items parsing (Single-line row format)
            Matcher itemMatcher = ITEM_ROW_PATTERN.matcher(line);
            if (itemMatcher.find()) {
                String desc = itemMatcher.group(2).trim();
                String hsn = itemMatcher.group(3).trim();
                BigDecimal qty = parseDecimal(itemMatcher.group(4));
                String uom = itemMatcher.group(5).trim().toUpperCase(Locale.ROOT);
                BigDecimal g6 = itemMatcher.group(6) != null ? parseDecimal(itemMatcher.group(6)) : BigDecimal.ZERO;
                String g7Str = itemMatcher.group(7) != null ? itemMatcher.group(7).trim() : null;
                BigDecimal g8 = itemMatcher.group(8) != null ? parseDecimal(itemMatcher.group(8)) : BigDecimal.ZERO;

                BigDecimal rate = g6;
                BigDecimal taxRate = new BigDecimal("18.00");
                BigDecimal lineAmount;

                if (g8.compareTo(BigDecimal.ZERO) > 0) {
                    lineAmount = g8;
                    if (g7Str != null && !g7Str.isBlank()) {
                        taxRate = parseDecimal(g7Str.replace("%", ""));
                    }
                } else if (g7Str != null && !g7Str.isBlank()) {
                    if (g7Str.endsWith("%")) {
                        taxRate = parseDecimal(g7Str.replace("%", ""));
                        lineAmount = qty.multiply(rate);
                    } else {
                        BigDecimal candidate = parseDecimal(g7Str);
                        if (candidate.compareTo(rate) > 0) {
                            lineAmount = candidate;
                        } else {
                            taxRate = candidate;
                            lineAmount = qty.multiply(rate);
                        }
                    }
                } else {
                    lineAmount = qty.multiply(rate);
                }

                if (rate.compareTo(BigDecimal.ZERO) == 0 && qty.compareTo(BigDecimal.ZERO) > 0 && lineAmount.compareTo(BigDecimal.ZERO) > 0) {
                    rate = lineAmount.divide(qty, 2, RoundingMode.HALF_UP);
                }

                items.add(new TallyAutomationApiClient.BillLineItemDto(
                        null,
                        null,
                        itemIndex++,
                        desc,
                        desc,
                        hsn,
                        qty,
                        uom,
                        rate,
                        BigDecimal.ZERO,
                        taxRate,
                        lineAmount,
                        new BigDecimal("100.00"),
                        ""
                ));
            }
        }

        // Align summary block labels if totals were not matched inline
        if (summaryLabels.size() == summaryNumbers.size() && !summaryLabels.isEmpty()) {
            for (int j = 0; j < summaryLabels.size(); j++) {
                String lbl = summaryLabels.get(j).toLowerCase(Locale.ROOT);
                BigDecimal val = summaryNumbers.get(j);
                if (lbl.contains("taxable") || lbl.contains("subtotal")) {
                    if (taxable == null || lbl.contains("combined")) taxable = val;
                } else if (lbl.contains("cgst")) {
                    cgst = val;
                } else if (lbl.contains("sgst")) {
                    sgst = val;
                } else if (lbl.contains("igst")) {
                    igst = val;
                } else if (lbl.contains("net payable") || lbl.contains("grand total") || lbl.contains("total")) {
                    if (grandTotal == null) grandTotal = val;
                }
            }
        }

        // 6. Columnar Block OCR Table Item Parsing (if single-line matching produced no items)
        if (items.isEmpty()) {
            List<String> blockDescriptions = new ArrayList<>();
            List<String> blockHsns = new ArrayList<>();
            List<BigDecimal> blockQtys = new ArrayList<>();
            List<String> blockUnits = new ArrayList<>();
            List<BigDecimal> blockRates = new ArrayList<>();
            List<BigDecimal> blockAmounts = new ArrayList<>();

            int colMode = 0; // 1=Desc, 2=HSN, 3=Qty, 4=Unit, 5=Rate, 6=Amount
            for (String line : lines) {
                String l = line.trim();
                if (l.equalsIgnoreCase("Particulars / Item Name") || l.equalsIgnoreCase("Description") || l.equalsIgnoreCase("Item Description")) { colMode = 1; continue; }
                if (l.equalsIgnoreCase("HSN/SAC") || l.equalsIgnoreCase("HSN")) { colMode = 2; continue; }
                if (l.equalsIgnoreCase("Qty") || l.equalsIgnoreCase("Quantity")) { colMode = 3; continue; }
                if (l.equalsIgnoreCase("Unit") || l.equalsIgnoreCase("UOM")) { colMode = 4; continue; }
                if (l.equalsIgnoreCase("Rate") || l.equalsIgnoreCase("Price")) { colMode = 5; continue; }
                if (l.equalsIgnoreCase("Net Amt") || l.equalsIgnoreCase("Amount") || l.equalsIgnoreCase("Total")) { colMode = 6; continue; }
                if (l.toLowerCase(Locale.ROOT).contains("brought forward") || l.toLowerCase(Locale.ROOT).contains("subtotal") || l.toLowerCase(Locale.ROOT).contains("payable") || l.toLowerCase(Locale.ROOT).contains("grand total")) {
                    colMode = 0;
                }

                switch (colMode) {
                    case 1 -> { if (l.length() > 2 && !l.matches("^[0-9.]+$") && !l.equalsIgnoreCase("Note.")) blockDescriptions.add(l); }
                    case 2 -> { if (l.matches("\\d{4,8}")) blockHsns.add(l); }
                    case 3 -> { BigDecimal q = parseDecimal(l); if (q.compareTo(BigDecimal.ZERO) > 0) blockQtys.add(q); }
                    case 4 -> { if (l.matches("(?i)[a-z]{2,5}")) blockUnits.add(l.toUpperCase(Locale.ROOT)); }
                    case 5 -> { BigDecimal r = parseDecimal(l); if (r.compareTo(BigDecimal.ZERO) > 0) blockRates.add(r); }
                    case 6 -> { BigDecimal a = parseDecimal(l); if (a.compareTo(BigDecimal.ZERO) > 0) blockAmounts.add(a); }
                }
            }

            int count = blockDescriptions.size();
            if (count > 0 && count == blockQtys.size()) {
                for (int idx = 0; idx < count; idx++) {
                    String desc = blockDescriptions.get(idx);
                    String hsn = (idx < blockHsns.size()) ? blockHsns.get(idx) : "998315";
                    BigDecimal qty = blockQtys.get(idx);
                    String uom = (idx < blockUnits.size()) ? blockUnits.get(idx) : "PCS";
                    BigDecimal rate = (idx < blockRates.size()) ? blockRates.get(idx) : BigDecimal.ZERO;
                    BigDecimal amount = (idx < blockAmounts.size()) ? blockAmounts.get(idx) : qty.multiply(rate);
                    if (rate.compareTo(BigDecimal.ZERO) == 0 && qty.compareTo(BigDecimal.ZERO) > 0 && amount.compareTo(BigDecimal.ZERO) > 0) {
                        rate = amount.divide(qty, 2, RoundingMode.HALF_UP);
                    }

                    items.add(new TallyAutomationApiClient.BillLineItemDto(
                            null,
                            null,
                            itemIndex++,
                            desc,
                            desc,
                            hsn,
                            qty,
                            uom,
                            rate,
                            BigDecimal.ZERO,
                            new BigDecimal("18.00"),
                            amount,
                            new BigDecimal("100.00"),
                            ""
                    ));
                }
            }
        }

        // Vendor name discovery fallback
        if (vendorName == null) {
            for (int i = 0; i < Math.min(8, lines.size()); i++) {
                String candidate = lines.get(i).trim();
                if (isProminentName(candidate)) {
                    vendorName = candidate;
                    break;
                }
            }
        }
        if (vendorName == null || vendorName.isBlank()) {
            vendorName = defaultPartyLedger != null && !defaultPartyLedger.isBlank() ? defaultPartyLedger : "";
        }

        if (invNo == null || invNo.isBlank()) {
            invNo = generateFallbackInvoiceNo(file.getName());
        }
        if (invDate == null) {
            invDate = LocalDate.now();
        }

        // Sum of line items
        BigDecimal itemSum = items.stream()
                .map(TallyAutomationApiClient.BillLineItemDto::taxableAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if ((taxable == null || taxable.compareTo(BigDecimal.ZERO) == 0) && itemSum.compareTo(BigDecimal.ZERO) > 0) {
            taxable = itemSum;
        }
        if (taxable == null) {
            taxable = BigDecimal.ZERO;
        }

        BigDecimal totalGst = cgst.add(sgst).add(igst);
        if (totalGst.compareTo(BigDecimal.ZERO) == 0 && taxable.compareTo(BigDecimal.ZERO) > 0) {
            boolean isInterState = vendorGstin != null && !vendorGstin.isBlank() && !vendorGstin.startsWith("24");
            totalGst = taxable.multiply(new BigDecimal("0.18")).setScale(2, RoundingMode.HALF_UP);
            if (isInterState) {
                igst = totalGst;
            } else {
                cgst = totalGst.divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
                sgst = totalGst.subtract(cgst);
            }
        }

        if (grandTotal == null || grandTotal.compareTo(BigDecimal.ZERO) == 0) {
            grandTotal = taxable.add(totalGst).setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal accuracy = calculateAccuracy(taxable, totalGst, grandTotal, items);

        String partyLedger = (defaultPartyLedger != null && !defaultPartyLedger.isBlank())
                ? defaultPartyLedger
                : (!vendorName.isBlank() ? vendorName : "Purchase Account");

        String status = (items.isEmpty() || accuracy.compareTo(new BigDecimal("90.00")) < 0) ? "NEEDS_REVIEW" : "EXTRACTED";

        return new TallyAutomationApiClient.BillRecordDto(
                null,
                batchId,
                clientProfileId,
                items.isEmpty() ? "ACCOUNTING" : "ITEM",
                invNo,
                invDate,
                vendorName,
                vendorGstin != null ? vendorGstin : "",
                partyLedger,
                "Purchase Account",
                null,
                null,
                taxable,
                cgst,
                sgst,
                igst,
                BigDecimal.ZERO,
                grandTotal,
                accuracy,
                items.isEmpty() ? "No line items detected. Please verify or inspect document." : "Document parsed with " + items.size() + " line items.",
                file.getAbsolutePath(),
                status,
                null,
                items
        );
    }

    private static TallyAutomationApiClient.BillRecordDto parseExcel(File file, Long batchId, Long clientProfileId, String defaultPartyLedger) {
        String invNo = generateFallbackInvoiceNo(file.getName());
        LocalDate invDate = LocalDate.now();
        String vendorName = defaultPartyLedger != null && !defaultPartyLedger.isBlank() ? defaultPartyLedger : "";
        String vendorGstin = "";
        BigDecimal taxable = BigDecimal.ZERO;
        BigDecimal cgst = BigDecimal.ZERO;
        BigDecimal sgst = BigDecimal.ZERO;
        BigDecimal igst = BigDecimal.ZERO;
        BigDecimal grandTotal = BigDecimal.ZERO;

        List<TallyAutomationApiClient.BillLineItemDto> items = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = WorkbookFactory.create(fis)) {
            Sheet sheet = workbook.getSheetAt(0);
            int rowIdx = 1;
            DataFormatter formatter = new DataFormatter();

            for (Row row : sheet) {
                if (row.getRowNum() == 0) continue; // Skip header
                String col0 = formatter.formatCellValue(row.getCell(0)).trim();
                String col1 = formatter.formatCellValue(row.getCell(1)).trim();
                String col2 = formatter.formatCellValue(row.getCell(2)).trim();
                String col3 = formatter.formatCellValue(row.getCell(3)).trim();
                String col4 = formatter.formatCellValue(row.getCell(4)).trim();

                if (!col0.isBlank()) {
                    if (col0.toLowerCase(Locale.ROOT).startsWith("inv-") || col0.matches("[A-Za-z0-9\\-_/]{3,20}")) {
                        invNo = col0;
                    }
                    if (col1.matches("\\d{2}[A-Z]{5}\\d{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}")) {
                        vendorGstin = col1;
                    }
                    if (!col2.isBlank() && !col2.matches("[\\d,.]+")) {
                        vendorName = col2;
                    }
                    BigDecimal amount = parseDecimal(col4.isBlank() ? col3 : col4);
                    if (amount.compareTo(BigDecimal.ZERO) > 0) {
                        taxable = taxable.add(amount);
                        items.add(new TallyAutomationApiClient.BillLineItemDto(
                                null,
                                null,
                                rowIdx++,
                                col2.isBlank() ? "Item " + rowIdx : col2,
                                col2.isBlank() ? "Item " + rowIdx : col2,
                                "847160",
                                BigDecimal.ONE,
                                "PCS",
                                amount,
                                BigDecimal.ZERO,
                                new BigDecimal("18.00"),
                                amount,
                                new BigDecimal("100.00"),
                                ""
                        ));
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Excel parse notice for {}: {}", file.getName(), ex.getMessage());
        }

        BigDecimal totalGst = BigDecimal.ZERO;
        if (taxable.compareTo(BigDecimal.ZERO) > 0) {
            totalGst = taxable.multiply(new BigDecimal("0.18")).setScale(2, RoundingMode.HALF_UP);
            cgst = totalGst.divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
            sgst = totalGst.subtract(cgst);
            grandTotal = taxable.add(totalGst);
        }

        BigDecimal accuracy = calculateAccuracy(taxable, totalGst, grandTotal, items);

        return new TallyAutomationApiClient.BillRecordDto(
                null,
                batchId,
                clientProfileId,
                items.isEmpty() ? "ACCOUNTING" : "ITEM",
                invNo,
                invDate,
                vendorName,
                vendorGstin,
                !vendorName.isBlank() ? vendorName : (defaultPartyLedger != null ? defaultPartyLedger : "Purchase Account"),
                "Purchase Account",
                null,
                null,
                taxable,
                cgst,
                sgst,
                igst,
                BigDecimal.ZERO,
                grandTotal,
                accuracy,
                items.isEmpty() ? "No excel rows mapped." : "Excel sheet rows mapped directly.",
                file.getAbsolutePath(),
                (items.isEmpty() || accuracy.compareTo(new BigDecimal("90.00")) < 0) ? "NEEDS_REVIEW" : "EXTRACTED",
                null,
                items
        );
    }

    private static TallyAutomationApiClient.BillRecordDto parseCsv(File file, Long batchId, Long clientProfileId, String defaultPartyLedger) {
        String invNo = generateFallbackInvoiceNo(file.getName());
        try {
            List<String> lines = Files.readAllLines(file.toPath());
            if (lines.size() > 1) {
                String[] parts = lines.get(1).split(",");
                if (parts.length > 0 && !parts[0].isBlank()) invNo = parts[0].trim();
            }
        } catch (Exception ignored) {}
        return parseFallback(file, batchId, clientProfileId, defaultPartyLedger);
    }

    private static TallyAutomationApiClient.BillRecordDto parseFallback(File file, Long batchId, Long clientProfileId, String defaultPartyLedger) {
        String invNo = generateFallbackInvoiceNo(file.getName());
        LocalDate invDate = LocalDate.now();
        String vendorName = defaultPartyLedger != null && !defaultPartyLedger.isBlank() ? defaultPartyLedger : "";
        BigDecimal zero = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        List<TallyAutomationApiClient.BillLineItemDto> items = List.of();

        return new TallyAutomationApiClient.BillRecordDto(
                null,
                batchId,
                clientProfileId,
                "ITEM",
                invNo,
                invDate,
                vendorName,
                "",
                !vendorName.isBlank() ? vendorName : "Purchase Account",
                "Purchase Account",
                null,
                null,
                zero,
                zero,
                zero,
                zero,
                zero,
                zero,
                zero,
                "Document requires manual data entry or inspection.",
                file.getAbsolutePath(),
                "NEEDS_REVIEW",
                null,
                items
        );
    }

    private static BigDecimal calculateAccuracy(BigDecimal taxable, BigDecimal totalGst, BigDecimal grandTotal, List<TallyAutomationApiClient.BillLineItemDto> items) {
        if (taxable == null || taxable.compareTo(BigDecimal.ZERO) <= 0 || grandTotal == null || grandTotal.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal score = new BigDecimal("100.00");
        BigDecimal expectedTotal = taxable.add(totalGst != null ? totalGst : BigDecimal.ZERO);
        BigDecimal diff = grandTotal.subtract(expectedTotal).abs();

        if (diff.compareTo(new BigDecimal("0.50")) > 0) {
            score = score.subtract(new BigDecimal("30.00"));
        } else if (diff.compareTo(BigDecimal.ZERO) > 0) {
            score = score.subtract(new BigDecimal("2.00"));
        }

        if (items == null || items.isEmpty()) {
            score = score.subtract(new BigDecimal("30.00"));
        } else {
            BigDecimal itemsSum = items.stream()
                    .map(item -> item.taxableAmount() != null ? item.taxableAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal itemDiff = taxable.subtract(itemsSum).abs();
            if (itemDiff.compareTo(new BigDecimal("0.50")) > 0) {
                score = score.subtract(new BigDecimal("30.00"));
            }
        }

        return score.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    private static String generateFallbackInvoiceNo(String filename) {
        String base = filename.replaceAll("(?i)\\.(pdf|xlsx|xls|csv|png|jpg|jpeg)", "").replaceAll("[^A-Za-z0-9\\-_]", "-");
        if (base.toUpperCase(Locale.ROOT).startsWith("INV") || base.toUpperCase(Locale.ROOT).startsWith("BILL")) {
            return base.toUpperCase(Locale.ROOT);
        }
        return "INV-" + Math.abs(filename.hashCode() % 9000 + 1000);
    }

    private static boolean isProminentName(String text) {
        if (text == null || text.isBlank() || text.length() < 3 || text.length() > 60) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("tax invoice") || lower.contains("original") || lower.contains("gstin")
                || lower.contains("bill to") || lower.contains("ship to") || lower.contains("buyer")
                || lower.contains("page ") || lower.contains("invoice no")) {
            return false;
        }
        return Character.isLetter(text.charAt(0));
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return LocalDate.now();
        String clean = raw.replaceAll("[^0-9A-Za-z]", "-");
        List<DateTimeFormatter> formatters = List.of(
                DateTimeFormatter.ofPattern("dd-MM-yyyy"),
                DateTimeFormatter.ofPattern("d-M-yyyy"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd"),
                DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH)
        );
        for (var fmt : formatters) {
            try {
                return LocalDate.parse(clean, fmt);
            } catch (Exception ignored) {}
        }
        return LocalDate.now();
    }

    private static BigDecimal parseDecimal(String raw) {
        if (raw == null || raw.isBlank()) return BigDecimal.ZERO;
        try {
            String clean = raw.replaceAll("[^0-9.]", "");
            if (clean.isBlank()) return BigDecimal.ZERO;
            return new BigDecimal(clean).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception ignored) {
            return BigDecimal.ZERO;
        }
    }
}
