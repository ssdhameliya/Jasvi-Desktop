package org.example.documentstudio.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.example.documentstudio.model.DocumentType;
import org.example.documentstudio.model.ExcelTemplate;
import org.example.documentstudio.model.TemplateData;
import org.example.documentstudio.model.TemplateCharge;
import org.example.invoice.calculation.InvoiceTaxCalculator;
import org.example.invoice.calculation.AmountInWordsConverter;
import org.example.invoice.model.InvoiceTotals;
import org.example.invoice.model.TaxInvoiceCharge;
import org.example.invoice.model.TaxInvoiceItem;
import org.example.shared.DocumentCalculationEngine;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Renders ERP data into a user-created Excel template while preserving workbook formatting. */
public final class ExcelTemplateRenderer {
    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.\\[\\]-]+)\\s*}}");
    private static final Pattern WHOLE_TOKEN = Pattern.compile("^\\s*\\{\\{\\s*([A-Za-z0-9_.\\[\\]-]+)\\s*}}\\s*$");
    private static final Pattern A1_REFERENCE = Pattern.compile("(?<![A-Za-z0-9_])(?:(?:'[^']+'|[A-Za-z_][A-Za-z0-9_.]*)!)?(\\$?)([A-Za-z]{1,3})(\\$?)([0-9]+)(?![A-Za-z0-9_])");
    private static final Pattern FORMULA_STRING_LITERAL = Pattern.compile("\"(?:[^\"]|\"\")*\"");

    public record ChargeData(String type,double amount,boolean taxable,double gstPercent,double taxAmount,double total) {}

    private ExcelTemplateRenderer() {}

    /** Renders the unsaved workbook from the JavaFX editor without adding a temporary template to the library. */
    /** Renders an unsaved workbook with a real ERP record for Excel Studio preview/validation. */
    public static Path render(ExcelTemplate template, TemplateData data, List<ChargeData> charges, Path output) throws IOException {
        if (template == null) throw new IOException("Excel template is required.");
        if (data == null) throw new IOException("Document data is required.");
        Files.createDirectories(output.toAbsolutePath().getParent());
        Path source = ExcelTemplateStorageService.sourceWorkbook(template);
        try (Workbook workbook = openDetachedWorkbook(source)) {
            validateKnownTokens(workbook, template.getDocumentType(), data);
            fillWorkbook(workbook, data, effectiveCharges(data, charges));
            try (OutputStream out = Files.newOutputStream(output)) { workbook.write(out); }
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("Excel template could not be rendered: " + rootMessage(error), error); }
        return output;
    }

    /**
     * Apache POI may open an OOXML package from File in read/write mode. Rendering expands rows and
     * replaces ERP tokens, so a renderer must never hold the persisted template package directly.
     */
    static Workbook openDetachedWorkbook(Path source) throws IOException {
        if (source == null || !Files.isRegularFile(source)) throw new IOException("Excel workbook is missing: " + source);
        byte[] snapshot = Files.readAllBytes(source);
        if (snapshot.length == 0) throw new IOException("Excel workbook is empty: " + source);
        try {
            return WorkbookFactory.create(new ByteArrayInputStream(snapshot));
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Excel workbook could not be opened from a safe snapshot: " + rootMessage(error), error);
        }
    }

    public static Path renderBuiltIn(DocumentType type, TemplateData data, List<ChargeData> charges, Path output) throws IOException {
        Files.createDirectories(output.toAbsolutePath().getParent());
        try (Workbook workbook = ExcelTemplateStorageService.starterWorkbook(type)) {
            validateKnownTokens(workbook, type, data);
            fillWorkbook(workbook, data, effectiveCharges(data, charges));
            try (OutputStream out = Files.newOutputStream(output)) { workbook.write(out); }
        }
        return output;
    }


    private static List<ChargeData> effectiveCharges(TemplateData data, List<ChargeData> supplied) {
        if (supplied != null && !supplied.isEmpty()) return supplied;
        if (data == null || data.charges().isEmpty()) return List.of();
        List<ChargeData> out = new ArrayList<>();
        for (TemplateCharge charge : data.charges())
            out.add(new ChargeData(charge.type(), charge.amount(), charge.taxable(), charge.gstPercent(), charge.taxAmount(), charge.total()));
        return List.copyOf(out);
    }

    public static void fillWorkbook(Workbook workbook, TemplateData data, List<ChargeData> charges) {
        Map<String,String> values = new LinkedHashMap<>(data.values());
        values.putAll(derivedExcelValues(data, charges));
        for (int s=0;s<workbook.getNumberOfSheets();s++) {
            Sheet sheet = workbook.getSheetAt(s);
            expandRepeatingRows(sheet, data.items(), charges, data.gstType());
            for (Row row : sheet) {
                for (Cell cell : row) {
                    if (cell.getCellType() != CellType.STRING) continue;
                    String text = cell.getStringCellValue();
                    if (text == null || text.isBlank()) continue;
                    String withoutImages = placeImages(workbook, sheet, cell, text, data.images());
                    writeReplacedValue(cell, withoutImages, replaceTokens(withoutImages, values));
                }
            }
        }
        // Repeating rows can contain relative Excel formulas. POI stores the adjusted
        // formulas below and Excel recalculates the final workbook on first open.
        workbook.setForceFormulaRecalculation(true);
        try {
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
        } catch (Exception ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
    }

    /** Excel-only derived values kept out of the shared PDF field catalog. */
    public static Map<String,String> derivedExcelValues(TemplateData data) {
        return derivedExcelValues(data, effectiveCharges(data, List.of()));
    }

    private static Map<String,String> derivedExcelValues(TemplateData data,List<ChargeData> charges) {
        if (data == null || data.items() == null || data.items().isEmpty()) return Map.of();
        try {
            List<ChargeData> safeCharges=charges==null?List.of():charges.stream().filter(Objects::nonNull).toList();
            List<TaxInvoiceCharge> invoiceCharges = safeCharges.stream()
                    .map(c -> new TaxInvoiceCharge(c.type(), c.amount(), c.taxable(), c.gstPercent()))
                    .toList();
            InvoiceTotals totals = InvoiceTaxCalculator.calculate(data.items(), invoiceCharges, data.gstType());
            double preRound = DocumentCalculationEngine.money(totals.grandTotal() - totals.roundOff());
            double chargesAmount=0,chargeTax=0,chargesTotal=0;
            for(ChargeData charge:safeCharges){
                DocumentCalculationEngine.ChargeResult result=DocumentCalculationEngine.charge(charge.amount(),charge.taxable(),charge.gstPercent());
                chargesAmount+=result.amount();chargeTax+=result.taxAmount();chargesTotal+=result.totalAmount();
            }
            Map<String,String> values = new LinkedHashMap<>();
            boolean interstate=DocumentCalculationEngine.taxMode(data.gstType())==DocumentCalculationEngine.TaxMode.IGST;
            values.put("totals.cgstAmount",interstate?"0.00":money(totals.cgst()));
            values.put("totals.sgstAmount",interstate?"0.00":money(totals.sgst()));
            values.put("totals.igstAmount",interstate?money(totals.igst()):"0.00");
            values.put("totals.gstAmount",money(DocumentCalculationEngine.money(totals.cgst()+totals.sgst()+totals.igst())));
            values.put("totals.chargesAmount",money(DocumentCalculationEngine.money(chargesAmount)));
            values.put("totals.chargeTaxAmount",money(DocumentCalculationEngine.money(chargeTax)));
            values.put("totals.chargesTotal",money(DocumentCalculationEngine.money(chargesTotal)));
            double grossBeforeTax = DocumentCalculationEngine.money(totals.basicAmount() - totals.discountAmount() + totals.chargesAmount());
            values.put("totals.grossBeforeTax", money(grossBeforeTax));
            values.put("totals.preRoundTotal", money(preRound));
            values.put("totals.roundOff", money(totals.roundOff()));
            values.put("totals.roundedGrandTotal", money(totals.grandTotal()));
            values.put("totals.amountInWords", "INR : " + AmountInWordsConverter.indianRupees(totals.grandTotal()));
            values.put("totals.amountInWordsText", AmountInWordsConverter.indianRupees(totals.grandTotal()));

            double effectiveGstRate = data.items().stream()
                    .mapToDouble(TaxInvoiceItem::getGstPercent)
                    .filter(r -> r > 0)
                    .findFirst()
                    .orElse(18.0);
            double nominalFullRate = DocumentCalculationEngine.percent(effectiveGstRate);
            double nominalHalfRate = nominalFullRate / 2.0;
            values.put("tax.gstRate", number(nominalFullRate));
            values.put("tax.cgstRate", number(nominalHalfRate));
            values.put("tax.sgstRate", number(nominalHalfRate));
            values.put("tax.igstRate", number(nominalFullRate));
            values.put("tax.cgstLabel", "CGST @ " + number(nominalHalfRate) + "%");
            values.put("tax.sgstLabel", "SGST @ " + number(nominalHalfRate) + "%");
            values.put("tax.igstLabel", "IGST @ " + number(nominalFullRate) + "%");
            values.put("tax.primaryLabel", interstate ? ("IGST @ " + number(nominalFullRate) + "%") : ("CGST @ " + number(nominalHalfRate) + "%"));
            values.put("tax.primaryAmount", interstate ? money(totals.igst()) : money(totals.cgst()));
            values.put("tax.secondaryLabel", interstate ? "" : ("SGST @ " + number(nominalHalfRate) + "%"));
            values.put("tax.secondaryAmount", interstate ? "0.00" : money(totals.sgst()));

            List<String> masterTypes = fetchMasterChargeTypes(data);
            Set<String> usedTypes = new LinkedHashSet<>();
            for (int slot = 1; slot <= 2; slot++) {
                String prefix = "charge[" + slot + "].";
                if (slot <= safeCharges.size()) {
                    ChargeData c = safeCharges.get(slot - 1);
                    String name = safe(c.type()).trim();
                    if (name.isBlank()) name = "Charges " + slot;
                    usedTypes.add(name.toLowerCase(Locale.ROOT));
                    values.put(prefix + "name", name);
                    values.put(prefix + "type", name);
                    values.put(prefix + "amount", money(c.amount()));
                    values.put(prefix + "gstPercent", number(c.gstPercent()));
                    values.put(prefix + "taxAmount", money(c.taxAmount()));
                    values.put(prefix + "total", money(c.total()));
                } else {
                    String fallbackName = masterTypes.stream()
                            .filter(t -> t != null && !t.isBlank() && !usedTypes.contains(t.trim().toLowerCase(Locale.ROOT)))
                            .findFirst()
                            .orElse("Charges " + slot);
                    usedTypes.add(fallbackName.toLowerCase(Locale.ROOT));
                    values.put(prefix + "name", fallbackName);
                    values.put(prefix + "type", fallbackName);
                    values.put(prefix + "amount", "0.00");
                    values.put(prefix + "gstPercent", "0");
                    values.put(prefix + "taxAmount", "0.00");
                    values.put(prefix + "total", "0.00");
                }
            }

            return Map.copyOf(values);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static List<String> fetchMasterChargeTypes(TemplateData data) {
        if (data != null && data.values() != null) {
            String fromValues = data.values().get("config.masterChargeTypes");
            if (fromValues != null && !fromValues.isBlank()) {
                List<String> list = Arrays.stream(fromValues.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .toList();
                if (!list.isEmpty()) return list;
            }
        }
        return List.of();
    }


    private static String placeImages(Workbook workbook, Sheet sheet, Cell cell, String text, Map<String, Path> images) {
        if (text == null || text.isBlank() || images == null || images.isEmpty()) return text;
        String result = text;
        for (Map.Entry<String, Path> entry : images.entrySet()) {
            String token = "{{" + entry.getKey() + "}}";
            if (!result.contains(token)) continue;
            Path image = entry.getValue();
            if (image != null && Files.isRegularFile(image)) {
                try { addImage(workbook, sheet, cell, image); }
                catch (Exception error) { System.err.println("[ExcelStudio] Image placeholder " + entry.getKey() + " could not be rendered: " + error.getMessage()); }
            }
            result = result.replace(token, "").trim();
        }
        return result;
    }

    private static void addImage(Workbook workbook, Sheet sheet, Cell cell, Path image) throws IOException {
        PreparedImage prepared = prepareImage(image);
        int pictureIndex = workbook.addPicture(prepared.bytes(), prepared.pictureType());
        Drawing<?> drawing = sheet.createDrawingPatriarch();
        CreationHelper helper = workbook.getCreationHelper();
        ClientAnchor anchor = helper.createClientAnchor();
        int row1 = cell.getRowIndex(), col1 = cell.getColumnIndex();
        CellRangeAddress merged = mergedAt(sheet, row1, col1);

        int startRow = row1;
        int startCol = col1;
        int endRow = merged == null ? Math.min(Math.max(sheet.getLastRowNum(), row1), row1 + 3) : merged.getLastRow();
        int endCol = merged == null ? Math.min(16383, col1 + 2) : merged.getLastColumn();

        // Calculate available bounding box in EMUs across the target cell / merged region
        int totalWidthEmu = 0;
        for (int c = startCol; c <= endCol; c++) {
            totalWidthEmu += org.apache.poi.util.Units.columnWidthToEMU(sheet.getColumnWidth(c));
        }

        int totalHeightEmu = 0;
        for (int r = startRow; r <= endRow; r++) {
            Row rRow = sheet.getRow(r);
            float hPt = rRow == null ? sheet.getDefaultRowHeightInPoints() : rRow.getHeightInPoints();
            totalHeightEmu += org.apache.poi.util.Units.toEMU(hPt);
        }

        // Apply a safe border clearance inset (2 pixels) so the picture canvas never touches or covers cell borders
        int insetEmu = org.apache.poi.util.Units.pixelToEMU(2);
        int usableWidthEmu = Math.max(insetEmu, totalWidthEmu - 2 * insetEmu);
        int usableHeightEmu = Math.max(insetEmu, totalHeightEmu - 2 * insetEmu);

        int imgWidth = Math.max(1, prepared.width());
        int imgHeight = Math.max(1, prepared.height());

        double imgAspect = (double) imgWidth / (double) imgHeight;
        double boxAspect = (double) usableWidthEmu / (double) usableHeightEmu;

        int drawWidthEmu = usableWidthEmu;
        int drawHeightEmu = usableHeightEmu;

        if (imgAspect > boxAspect) {
            drawWidthEmu = usableWidthEmu;
            drawHeightEmu = Math.max(1, (int) Math.round(usableWidthEmu / imgAspect));
        } else {
            drawHeightEmu = usableHeightEmu;
            drawWidthEmu = Math.max(1, (int) Math.round(usableHeightEmu * imgAspect));
        }

        int offsetX = insetEmu + Math.max(0, (usableWidthEmu - drawWidthEmu) / 2);
        int offsetY = insetEmu + Math.max(0, (usableHeightEmu - drawHeightEmu) / 2);

        // Map X EMU offsets into (anchorCol1, anchorDx1) and (anchorCol2, anchorDx2)
        int curX = 0;
        int anchorCol1 = startCol;
        int anchorDx1 = 0;
        for (int c = startCol; c <= endCol; c++) {
            int colW = org.apache.poi.util.Units.columnWidthToEMU(sheet.getColumnWidth(c));
            if (curX + colW > offsetX || c == endCol) {
                anchorCol1 = c;
                anchorDx1 = Math.max(0, offsetX - curX);
                break;
            }
            curX += colW;
        }

        int targetRightX = offsetX + drawWidthEmu;
        curX = 0;
        int anchorCol2 = startCol;
        int anchorDx2 = 0;
        for (int c = startCol; c <= endCol; c++) {
            int colW = org.apache.poi.util.Units.columnWidthToEMU(sheet.getColumnWidth(c));
            if (curX + colW >= targetRightX || c == endCol) {
                anchorCol2 = c;
                anchorDx2 = Math.min(colW, Math.max(0, targetRightX - curX));
                break;
            }
            curX += colW;
        }

        // Map Y EMU offsets into (anchorRow1, anchorDy1) and (anchorRow2, anchorDy2)
        int curY = 0;
        int anchorRow1 = startRow;
        int anchorDy1 = 0;
        for (int r = startRow; r <= endRow; r++) {
            Row rRow = sheet.getRow(r);
            float hPt = rRow == null ? sheet.getDefaultRowHeightInPoints() : rRow.getHeightInPoints();
            int rowH = org.apache.poi.util.Units.toEMU(hPt);
            if (curY + rowH > offsetY || r == endRow) {
                anchorRow1 = r;
                anchorDy1 = Math.max(0, offsetY - curY);
                break;
            }
            curY += rowH;
        }

        int targetBottomY = offsetY + drawHeightEmu;
        curY = 0;
        int anchorRow2 = startRow;
        int anchorDy2 = 0;
        for (int r = startRow; r <= endRow; r++) {
            Row rRow = sheet.getRow(r);
            float hPt = rRow == null ? sheet.getDefaultRowHeightInPoints() : rRow.getHeightInPoints();
            int rowH = org.apache.poi.util.Units.toEMU(hPt);
            if (curY + rowH >= targetBottomY || r == endRow) {
                anchorRow2 = r;
                anchorDy2 = Math.min(rowH, Math.max(0, targetBottomY - curY));
                break;
            }
            curY += rowH;
        }

        anchor.setCol1(anchorCol1);
        anchor.setDx1(anchorDx1);
        anchor.setRow1(anchorRow1);
        anchor.setDy1(anchorDy1);

        anchor.setCol2(anchorCol2);
        anchor.setDx2(anchorDx2);
        anchor.setRow2(anchorRow2);
        anchor.setDy2(anchorDy2);

        anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_AND_RESIZE);
        drawing.createPicture(anchor, pictureIndex);
    }

    private record PreparedImage(byte[] bytes, int pictureType, int width, int height) {}

    private static PreparedImage prepareImage(Path image) throws IOException {
        byte[] original = Files.readAllBytes(image);
        int picType = pictureType(image);
        if (picType < 0) throw new IOException("Only PNG and JPEG images are supported for Excel image placeholders.");

        try {
            java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(image.toFile());
            if (src == null) return new PreparedImage(original, picType, 300, 150);

            int w = src.getWidth();
            int h = src.getHeight();

            // Detect scanned physical paper background: sample 4 corners
            java.awt.image.BufferedImage argb = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g2 = argb.createGraphics();
            g2.drawImage(src, 0, 0, null);
            g2.dispose();

            int[] corners = {
                argb.getRGB(0, 0),
                argb.getRGB(w - 1, 0),
                argb.getRGB(0, h - 1),
                argb.getRGB(w - 1, h - 1)
            };
            boolean isOpaqueWhitePaper = true;
            for (int c : corners) {
                int a = (c >> 24) & 0xff;
                int r = (c >> 16) & 0xff;
                int g = (c >> 8) & 0xff;
                int b = c & 0xff;
                if (a < 50) { isOpaqueWhitePaper = false; break; }
                if (r < 230 || g < 230 || b < 230) { isOpaqueWhitePaper = false; break; }
            }

            if (isOpaqueWhitePaper) {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int p = argb.getRGB(x, y);
                        int r = (p >> 16) & 0xff;
                        int g = (p >> 8) & 0xff;
                        int b = p & 0xff;
                        if (r >= 235 && g >= 235 && b >= 235) {
                            argb.setRGB(x, y, 0x00FFFFFF);
                        }
                    }
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                javax.imageio.ImageIO.write(argb, "PNG", baos);
                return new PreparedImage(baos.toByteArray(), Workbook.PICTURE_TYPE_PNG, w, h);
            }

            return new PreparedImage(original, picType, w, h);
        } catch (Throwable error) {
            return new PreparedImage(original, picType, 300, 150);
        }
    }

    private static CellRangeAddress mergedAt(Sheet sheet, int row, int col) {
        for (int i = 0; i < sheet.getNumMergedRegions(); i++) {
            CellRangeAddress region = sheet.getMergedRegion(i);
            if (region.isInRange(row, col)) return region;
        }
        return null;
    }

    private static int pictureType(Path image) {
        String name = image.getFileName() == null ? "" : image.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) return Workbook.PICTURE_TYPE_PNG;
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return Workbook.PICTURE_TYPE_JPEG;
        return -1;
    }

    private record RepeatBlock(int startRow, int endRow, boolean item) {
        int height() { return endRow - startRow + 1; }
    }

    /**
     * Excel Studio allows a repeating item template to span more than one contiguous row. This
     * validator is intentionally shared by the designer and renderer so "mapped" and
     * "renderable" can no longer disagree simply because Description is on one row while
     * Quantity/Rate/Amount are on the next row.
     */
    public static boolean hasCompleteItemRepeatingBlock(Workbook workbook) {
        if (workbook == null) return false;
        for (int si = 0; si < workbook.getNumberOfSheets(); si++) {
            Sheet sheet = workbook.getSheetAt(si);
            for (RepeatBlock block : findRepeatingBlocks(sheet, "{{item.", true)) {
                Set<String> keys = new LinkedHashSet<>();
                Set<Long> mappedCells = new LinkedHashSet<>();
                for (int r = block.startRow(); r <= block.endRow(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    for (Cell cell : row) {
                        if (cell.getCellType() != CellType.STRING) continue;
                        Matcher matcher = TOKEN.matcher(cell.getStringCellValue());
                        while (matcher.find()) {
                            String key = matcher.group(1);
                            if (!key.startsWith("item.")) continue;
                            keys.add(key);
                            mappedCells.add(cellKey(r, cell.getColumnIndex()));
                        }
                    }
                }
                boolean hasDescription = keys.contains("item.description")
                        || keys.contains("item.remarks")
                        || keys.contains("item.descriptionWithRemarks");
                boolean hasCore = hasDescription && keys.contains("item.quantity") && keys.contains("item.rate");
                boolean hasLineAmount = keys.contains("item.taxable") || keys.contains("item.total")
                        || hasValidBlockLineFormula(sheet, block, mappedCells);
                if (hasCore && hasLineAmount) return true;
            }
        }
        return false;
    }

    private static void expandRepeatingRows(Sheet sheet, List<TaxInvoiceItem> items, List<ChargeData> charges, String gstType) {
        List<RepeatBlock> blocks = new ArrayList<>();
        blocks.addAll(findRepeatingBlocks(sheet, "{{item.", true));
        blocks.addAll(findRepeatingBlocks(sheet, "{{charge.", false));
        blocks.sort(Comparator.comparingInt(RepeatBlock::startRow).reversed());
        for (RepeatBlock block : blocks) {
            if (block.item()) {
                removeDuplicateItemHighlighting(sheet, block);
                clearStaleRepeatingValues(sheet, block, "{{item.");
                expandItems(sheet, block, items == null ? List.of() : items, gstType);
            } else {
                clearStaleRepeatingValues(sheet, block, "{{charge.");
                expandCharges(sheet, block, charges == null ? List.of() : charges, gstType);
            }
        }
    }

    /**
     * A user workbook may contain Excel's "Duplicate Values" conditional formatting on a mapped
     * item column (commonly Product Description). Repeating ERP rows must keep the template's
     * normal cell style even when item values repeat, so remove only duplicate-value rules that
     * target mapped item columns. Other conditional formatting remains intact.
     */
    private static void removeDuplicateItemHighlighting(Sheet sheet, RepeatBlock block) {
        if (sheet == null || block == null || !block.item()) return;
        Set<Integer> itemColumns = new LinkedHashSet<>();
        for (int r = block.startRow(); r <= block.endRow(); r++)
            itemColumns.addAll(mappedColumns(sheet.getRow(r), "{{item."));
        if (itemColumns.isEmpty()) return;

        SheetConditionalFormatting sheetFormatting = sheet.getSheetConditionalFormatting();
        for (int index = sheetFormatting.getNumConditionalFormattings() - 1; index >= 0; index--) {
            ConditionalFormatting formatting = sheetFormatting.getConditionalFormattingAt(index);
            if (formatting == null || !formattingTouchesItemRegion(formatting, sheet, block, itemColumns)) continue;

            List<ConditionalFormattingRule> retained = new ArrayList<>();
            boolean duplicateRemoved = false;
            for (int ruleIndex = 0; ruleIndex < formatting.getNumberOfRules(); ruleIndex++) {
                ConditionalFormattingRule rule = formatting.getRule(ruleIndex);
                if (isDuplicateValueRule(rule)) duplicateRemoved = true;
                else retained.add(rule);
            }
            if (!duplicateRemoved) continue;

            CellRangeAddress[] ranges = formatting.getFormattingRanges();
            if (!retained.isEmpty())
                sheetFormatting.addConditionalFormatting(ranges, retained.toArray(new ConditionalFormattingRule[0]));
            sheetFormatting.removeConditionalFormatting(index);
        }
    }

    private static boolean formattingTouchesItemRegion(ConditionalFormatting formatting, Sheet sheet, RepeatBlock block, Set<Integer> itemColumns) {
        int lastRelevantRow = Math.max(block.endRow(), sheet.getLastRowNum());
        for (CellRangeAddress range : formatting.getFormattingRanges()) {
            if (range.getLastRow() < block.startRow() || range.getFirstRow() > lastRelevantRow) continue;
            for (int column : itemColumns)
                if (column >= range.getFirstColumn() && column <= range.getLastColumn()) return true;
        }
        return false;
    }

    private static boolean isDuplicateValueRule(ConditionalFormattingRule rule) {
        if (rule == null) return false;
        try {
            if (rule.getConditionType() == ConditionType.FILTER
                    && rule.getConditionFilterType() == ConditionFilterType.DUPLICATE_VALUES) return true;
        } catch (Exception ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
        String formula = rule.getFormula1();
        if (formula == null) return false;
        String normalized = formula.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        return normalized.contains("COUNTIF(") && (normalized.contains(">1") || normalized.contains(">=2"));
    }

    /** Clears literal sample values only in columns owned by the repeating ERP block. */
    private static void clearStaleRepeatingValues(Sheet sheet, RepeatBlock block, String marker) {
        int firstCandidate = block.endRow() + 1;
        for (int r = firstCandidate; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null || rowIsBlank(row) || rowContainsToken(row) || looksLikeSummaryRow(row)) break;
            int offset = (r - firstCandidate) % block.height();
            Row template = sheet.getRow(block.startRow() + offset);
            if (template == null) break;
            Set<Integer> mappedColumns = mappedColumns(template, marker);
            if (mappedColumns.isEmpty()) break;
            if (!looksLikeRepeatingDataRow(template, row, mappedColumns)) break;
            // The literal sample row is now owned by the ERP repeater. Normalize mapped
            // cells to the canonical template-row style so old pink/red sample formatting does
            // not appear on arbitrary generated items.
            row.setHeight(template.getHeight());
            for (int c : mappedColumns) {
                Cell templateCell = template.getCell(c);
                Cell cell = row.getCell(c);
                if (cell == null && templateCell != null) cell = row.createCell(c, CellType.BLANK);
                if (cell != null) {
                    if (templateCell != null) cell.setCellStyle(templateCell.getCellStyle());
                    cell.setBlank();
                }
            }
        }
    }

    private static Set<Integer> mappedColumns(Row row, String marker) {
        Set<Integer> mappedColumns = new LinkedHashSet<>();
        if (row == null) return mappedColumns;
        for (Cell cell : row)
            if (cell.getCellType() == CellType.STRING && cell.getStringCellValue().contains(marker))
                mappedColumns.add(cell.getColumnIndex());
        return mappedColumns;
    }

    private static boolean looksLikeRepeatingDataRow(Row template, Row candidate, Set<Integer> mappedColumns) {
        int nonBlank = 0, styleComparable = 0, styleMatches = 0;
        for (int c : mappedColumns) {
            Cell cell = candidate.getCell(c);
            if (cell == null || cell.getCellType() == CellType.BLANK || (cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank())) continue;
            nonBlank++;
            Cell templateCell = template.getCell(c);
            if (templateCell != null) {
                styleComparable++;
                if (templateCell.getCellStyle().getIndex() == cell.getCellStyle().getIndex()) styleMatches++;
            }
        }
        if (nonBlank == 0) return false;
        if (styleComparable > 0 && styleMatches * 2 >= styleComparable) return true;
        return nonBlank >= Math.min(3, mappedColumns.size());
    }

    private static boolean looksLikeSummaryRow(Row row) {
        for (Cell cell : row) {
            if (cell.getCellType() != CellType.STRING) continue;
            String value = cell.getStringCellValue().trim().toLowerCase(Locale.ROOT);
            if (value.matches(".*\\b(sub\\s*total|subtotal|grand\\s+total|total\\s+gst|cgst|sgst|igst|amount\\s+in\\s+words|round\\s*off|balance|paid\\s+amount)\\b.*")) return true;
        }
        return false;
    }

    private static boolean rowContainsToken(Row row) {
        for (Cell cell : row) if (cell.getCellType() == CellType.STRING && TOKEN.matcher(cell.getStringCellValue()).find()) return true;
        return false;
    }

    private static boolean rowIsBlank(Row row) {
        for (Cell cell : row) {
            if (cell.getCellType() == CellType.BLANK) continue;
            if (cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank()) continue;
            return false;
        }
        return true;
    }

    private static List<RepeatBlock> findRepeatingBlocks(Sheet sheet, String marker, boolean item) {
        List<RepeatBlock> blocks = new ArrayList<>();
        int start = -1, previous = -2;
        for (int r=sheet.getFirstRowNum();r<=sheet.getLastRowNum();r++) {
            Row row=sheet.getRow(r); if(row==null)continue;
            boolean contains = false;
            for(Cell cell:row) if(cell.getCellType()==CellType.STRING && cell.getStringCellValue().contains(marker)) { contains = true; break; }
            if (!contains) continue;
            if (start < 0 || r != previous + 1) {
                if (start >= 0) blocks.add(new RepeatBlock(start, previous, item));
                start = r;
            }
            previous = r;
        }
        if (start >= 0) blocks.add(new RepeatBlock(start, previous, item));
        return blocks;
    }

    private static void expandItems(Sheet sheet, RepeatBlock block, List<TaxInvoiceItem> items, String gstType) {
        int count = Math.max(1, items.size());
        List<CellRangeAddress> blockMerges = mergedRegionsInside(sheet, block);
        int extraRows = block.height() * (count - 1);
        if (extraRows > 0 && sheet.getLastRowNum() >= block.endRow()+1)
            sheet.shiftRows(block.endRow()+1, sheet.getLastRowNum(), extraRows, true, false);
        // Clone every destination while all source rows still contain ERP tokens. Filling the first
        // block before cloning would copy the first item's resolved values into every later block.
        for (int i=1;i<count;i++) copyBlock(sheet, block, i, blockMerges);
        for (int i=0;i<count;i++) {
            TaxInvoiceItem item = items.isEmpty() ? null : items.get(i);
            Map<String,String> values = itemValues(item, i+1, gstType);
            int destinationStart = block.startRow() + i * block.height();
            for (int offset = 0; offset < block.height(); offset++)
                fillRow(sheet.getRow(destinationStart + offset), values);
        }
    }

    private static void expandCharges(Sheet sheet, RepeatBlock block, List<ChargeData> charges, String gstType) {
        if (charges.isEmpty()) {
            for (int r = block.startRow(); r <= block.endRow(); r++) clearRepeatingRow(sheet.getRow(r), "{{charge.");
            return;
        }
        int count=charges.size();
        List<CellRangeAddress> blockMerges = mergedRegionsInside(sheet, block);
        int extraRows = block.height() * (count - 1);
        if(extraRows>0&&sheet.getLastRowNum()>=block.endRow()+1)
            sheet.shiftRows(block.endRow()+1,sheet.getLastRowNum(),extraRows,true,false);
        for(int i=1;i<count;i++)copyBlock(sheet,block,i,blockMerges);
        for(int i=0;i<count;i++){
            int destinationStart = block.startRow() + i * block.height();
            Map<String,String> values = chargeValues(charges.get(i), i+1, gstType);
            for (int offset = 0; offset < block.height(); offset++)
                fillRow(sheet.getRow(destinationStart + offset), values);
        }
    }

    private static void copyBlock(Sheet sheet, RepeatBlock block, int copyIndex, List<CellRangeAddress> blockMerges) {
        int delta = copyIndex * block.height();
        for (int offset = 0; offset < block.height(); offset++) {
            Row source = sheet.getRow(block.startRow() + offset);
            if (source != null) copyRow(sheet, source, source.getRowNum() + delta);
        }
        for (CellRangeAddress source : blockMerges) {
            CellRangeAddress copy = new CellRangeAddress(
                    source.getFirstRow() + delta, source.getLastRow() + delta,
                    source.getFirstColumn(), source.getLastColumn());
            sheet.addMergedRegion(copy);
        }
    }

    private static List<CellRangeAddress> mergedRegionsInside(Sheet sheet, RepeatBlock block) {
        List<CellRangeAddress> regions = new ArrayList<>();
        for (int i = 0; i < sheet.getNumMergedRegions(); i++) {
            CellRangeAddress region = sheet.getMergedRegion(i);
            if (region.getFirstRow() >= block.startRow() && region.getLastRow() <= block.endRow())
                regions.add(new CellRangeAddress(region.getFirstRow(), region.getLastRow(), region.getFirstColumn(), region.getLastColumn()));
        }
        return regions;
    }

    private static Row copyRow(Sheet sheet, Row source, int targetIndex) {
        Row target = sheet.createRow(targetIndex);
        target.setHeight(source.getHeight());
        for (int c=source.getFirstCellNum();c<source.getLastCellNum();c++) {
            if(c<0)continue; Cell sc=source.getCell(c); if(sc==null)continue;
            Cell tc=target.createCell(c, sc.getCellType()); tc.setCellStyle(sc.getCellStyle());
            switch(sc.getCellType()){
                case STRING -> tc.setCellValue(sc.getStringCellValue());
                case NUMERIC -> tc.setCellValue(sc.getNumericCellValue());
                case BOOLEAN -> tc.setCellValue(sc.getBooleanCellValue());
                case FORMULA -> tc.setCellFormula(shiftFormulaForCopiedRow(sc.getCellFormula(), targetIndex - source.getRowNum()));
                case ERROR -> tc.setCellErrorValue(sc.getErrorCellValue());
                default -> { }
            }
        }
        return target;
    }

    private static void fillRow(Row row, Map<String,String> values) {
        if (row == null) return;
        for(Cell cell:row){
            if(cell.getCellType()!=CellType.STRING)continue;
            String text=cell.getStringCellValue(); if(text==null)continue;
            String replaced=replaceTokens(text,values);
            Matcher whole=WHOLE_TOKEN.matcher(text);
            if(whole.matches()&&"item.descriptionWithRemarks".equals(whole.group(1)))ensureWrap(cell);
            // If the whole cell is a numeric repeating token, store a real number for formulas/sorting.
            if(text.trim().matches("\\{\\{\\s*(item|charge)\\.[A-Za-z0-9_.-]+\\s*}}") && replaced.matches("-?\\d+(\\.\\d+)?")){
                try{cell.setCellValue(Double.parseDouble(replaced));continue;}catch (Exception ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
            }
            cell.setCellValue(replaced);
        }
    }

    private static void clearRepeatingRow(Row row, String marker) {
        if (row == null) return;
        for(Cell cell:row)if(cell.getCellType()==CellType.STRING&&cell.getStringCellValue().contains(marker))cell.setBlank();
    }

    private static boolean hasValidBlockLineFormula(Sheet sheet, RepeatBlock block, Set<Long> mappedCells) {
        if (sheet == null || mappedCells == null || mappedCells.isEmpty()) return false;
        for (int r = block.startRow(); r <= block.endRow(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (Cell cell : row) {
                if (cell.getCellType() != CellType.FORMULA) continue;
                String formulaCode = FORMULA_STRING_LITERAL.matcher(cell.getCellFormula()).replaceAll("");
                Matcher refs = A1_REFERENCE.matcher(formulaCode);
                int blockRefs = 0;
                boolean touchesMappedItem = false;
                while (refs.find()) {
                    int refRow;
                    int refCol;
                    try {
                        refRow = Integer.parseInt(refs.group(4)) - 1;
                        refCol = CellReference.convertColStringToIndex(refs.group(2));
                    } catch (Exception ignored) { continue; }
                    if (refRow < block.startRow() || refRow > block.endRow()) continue;
                    blockRefs++;
                    if (mappedCells.contains(cellKey(refRow, refCol))) touchesMappedItem = true;
                }
                if (blockRefs >= 2 && touchesMappedItem) return true;
            }
        }
        return false;
    }

    private static long cellKey(int row, int col) {
        return (((long) row) << 32) ^ (col & 0xffffffffL);
    }

    private static Map<String,String> itemValues(TaxInvoiceItem item,int serial,String gstType) {
        Map<String,String> v=new HashMap<>();
        List<String> keys=List.of("serial","code","description","descriptionWithRemarks","remarks","category","brand","material","size","hsn","quantity","unit","rate","discountPercent","discountAmount","taxable","gstPercent","gstAmount","cgstPercent","cgstAmount","sgstPercent","sgstAmount","igstPercent","igstAmount","total","location","purchasePrice","sellingPrice","availableStock","openingStock","minimumStock","reservedStock","masterGstPercent","masterDiscountPercent");
        if(item==null){ for(String k:keys)v.put("item."+k,""); return v; }
        DocumentCalculationEngine.LineResult result=DocumentCalculationEngine.line(item.getQuantity(),item.getRate(),item.getDiscountPercent(),item.getGstPercent());
        TaxSplit split=taxSplit(item.getGstPercent(),result.taxAmount(),gstType);
        v.put("item.serial",Integer.toString(serial));
        v.put("item.code",safe(item.getItemCode()));
        // Description cells always include the optional line remark. A blank remark adds nothing.
        v.put("item.description",descriptionWithRemarks(item.getDescription(),item.getRemarks()));
        v.put("item.descriptionWithRemarks",descriptionWithRemarks(item.getDescription(),item.getRemarks()));
        v.put("item.remarks",safe(item.getRemarks()));
        v.put("item.category",safe(item.getCategory()));
        v.put("item.brand",safe(item.getBrand()));
        v.put("item.material",safe(item.getMaterial()));
        v.put("item.size",safe(item.getSize()));
        v.put("item.hsn",safe(item.getHsn()));
        v.put("item.quantity",quantityNumber(item.getQuantity()));v.put("item.unit",safe(item.getUnit()));v.put("item.rate",money(item.getRate()));
        v.put("item.discountPercent",number(item.getDiscountPercent()));v.put("item.discountAmount",money(result.discountAmount()));v.put("item.taxable",money(result.taxableAmount()));
        v.put("item.gstPercent",number(item.getGstPercent()));v.put("item.gstAmount",money(result.taxAmount()));
        boolean interstate=DocumentCalculationEngine.taxMode(gstType)==DocumentCalculationEngine.TaxMode.IGST;
        v.put("item.cgstPercent",interstate?"":number(split.cgstPercent()));v.put("item.cgstAmount",interstate?"":money(split.cgstAmount()));
        v.put("item.sgstPercent",interstate?"":number(split.sgstPercent()));v.put("item.sgstAmount",interstate?"":money(split.sgstAmount()));
        v.put("item.igstPercent",interstate?number(split.igstPercent()):"");v.put("item.igstAmount",interstate?money(split.igstAmount()):"");
        v.put("item.total",money(result.totalAmount()));
        v.put("item.location",safe(item.getLocation()));
        v.put("item.purchasePrice",money(item.getPurchasePrice()));
        v.put("item.sellingPrice",money(item.getSellingPrice()));
        v.put("item.availableStock",number(item.getAvailableStock()));
        v.put("item.openingStock",number(item.getOpeningStock()));
        v.put("item.minimumStock",number(item.getMinimumStock()));
        v.put("item.reservedStock",number(item.getReservedStock()));
        v.put("item.masterGstPercent",number(item.getMasterGstPercent()));
        v.put("item.masterDiscountPercent",number(item.getMasterDiscountPercent()));
        return v;
    }

    private static Map<String,String> chargeValues(ChargeData c,int serial,String gstType) {
        Map<String,String> v=new HashMap<>();
        DocumentCalculationEngine.ChargeResult result=DocumentCalculationEngine.charge(c.amount(),c.taxable(),c.gstPercent());
        TaxSplit split=taxSplit(c.gstPercent(),result.taxAmount(),gstType);
        v.put("charge.serial",Integer.toString(serial));
        String chargeType=safe(c.type()).trim();
        v.put("charge.type",chargeType.isBlank()?"Charges":chargeType);v.put("charge.amount",money(result.amount()));v.put("charge.taxable",c.taxable()?"Yes":"No");
        v.put("charge.taxableAmount",money(result.taxableAmount()));
        v.put("charge.gstPercent",number(c.taxable()?c.gstPercent():0));v.put("charge.taxAmount",money(result.taxAmount()));
        boolean interstate=DocumentCalculationEngine.taxMode(gstType)==DocumentCalculationEngine.TaxMode.IGST;
        v.put("charge.cgstPercent",interstate?"":number(split.cgstPercent()));v.put("charge.cgstAmount",interstate?"":money(split.cgstAmount()));
        v.put("charge.sgstPercent",interstate?"":number(split.sgstPercent()));v.put("charge.sgstAmount",interstate?"":money(split.sgstAmount()));
        v.put("charge.igstPercent",interstate?number(split.igstPercent()):"");v.put("charge.igstAmount",interstate?money(split.igstAmount()):"");
        v.put("charge.total",money(result.totalAmount()));
        return v;
    }

    private record TaxSplit(double cgstPercent,double cgstAmount,double sgstPercent,double sgstAmount,double igstPercent,double igstAmount) { }

    private static TaxSplit taxSplit(double gstPercent,double taxAmount,String gstType) {
        double rate=DocumentCalculationEngine.percent(gstPercent);
        double tax=DocumentCalculationEngine.money(taxAmount);
        if(DocumentCalculationEngine.taxMode(gstType)==DocumentCalculationEngine.TaxMode.IGST)
            return new TaxSplit(0,0,0,0,rate,tax);
        double cgstRate=rate/2d;
        double sgstRate=rate-cgstRate;
        double cgst=DocumentCalculationEngine.money(tax/2d);
        double sgst=DocumentCalculationEngine.money(tax-cgst);
        return new TaxSplit(cgstRate,cgst,sgstRate,sgst,0,0);
    }

    /** Shift relative A1-style row references when a repeating template row is copied. String literals remain unchanged. */
    static String shiftFormulaForCopiedRow(String formula,int rowDelta) {
        if(formula==null||formula.isBlank()||rowDelta==0)return formula;
        StringBuilder out=new StringBuilder(formula.length()+8);
        int segmentStart=0;boolean quoted=false;
        for(int i=0;i<formula.length();i++){
            if(formula.charAt(i)!='\"')continue;
            if(quoted&&i+1<formula.length()&&formula.charAt(i+1)=='\"'){i++;continue;}
            if(!quoted){
                out.append(shiftFormulaSegment(formula.substring(segmentStart,i),rowDelta));
                quoted=true;segmentStart=i;
            }else{
                out.append(formula,segmentStart,i+1);
                quoted=false;segmentStart=i+1;
            }
        }
        if(quoted)out.append(formula.substring(segmentStart));
        else out.append(shiftFormulaSegment(formula.substring(segmentStart),rowDelta));
        return out.toString();
    }

    private static String shiftFormulaSegment(String segment,int rowDelta){
        Matcher matcher=A1_REFERENCE.matcher(segment);
        StringBuffer out=new StringBuffer();
        while(matcher.find()){
            String full=matcher.group();
            if(!matcher.group(3).isEmpty()){matcher.appendReplacement(out,Matcher.quoteReplacement(full));continue;}
            int row;
            try{row=Integer.parseInt(matcher.group(4));}catch(Exception e){matcher.appendReplacement(out,Matcher.quoteReplacement(full));continue;}
            int shifted=Math.max(1,row+rowDelta);
            String replacement=full.substring(0,full.length()-matcher.group(4).length())+shifted;
            matcher.appendReplacement(out,Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public static List<String> unknownTokens(Workbook workbook, DocumentType type, TemplateData data) {
        Set<String> allowed = new LinkedHashSet<>();
        if (type != null) TemplateFieldCatalog.excelFieldsFor(type).forEach(field -> allowed.add(field.key()));
        else for (DocumentType candidate : DocumentType.values()) TemplateFieldCatalog.excelFieldsFor(candidate).forEach(field -> allowed.add(field.key()));
        if (data != null) allowed.addAll(data.images().keySet());
        Set<String> unknown = new LinkedHashSet<>();
        if (workbook == null) return List.of();
        for (int s=0;s<workbook.getNumberOfSheets();s++) for (Row row : workbook.getSheetAt(s)) for (Cell cell : row) {
            if (cell.getCellType()!=CellType.STRING) continue;
            Matcher matcher=TOKEN.matcher(cell.getStringCellValue());
            while(matcher.find()) if(!allowed.contains(matcher.group(1))) unknown.add(matcher.group(1)+" @ "+workbook.getSheetName(s)+"!"+cell.getAddress().formatAsString());
        }
        return List.copyOf(unknown);
    }

    /** Backward-compatible broad validation for callers that do not know the document type. */
    public static List<String> unknownTokens(Workbook workbook, TemplateData data) { return unknownTokens(workbook, null, data); }

    private static void validateKnownTokens(Workbook workbook, DocumentType type, TemplateData data) {
        List<String> unknown = unknownTokens(workbook, type, data);
        if (!unknown.isEmpty()) throw new IllegalArgumentException("Unsupported ERP field(s): " + String.join(", ", unknown));
    }

    private static String replaceTokens(String text, Map<String,String> values) {
        Matcher m=TOKEN.matcher(text);StringBuffer out=new StringBuffer();
        while(m.find())m.appendReplacement(out,Matcher.quoteReplacement(values.getOrDefault(m.group(1),"")));
        m.appendTail(out);return out.toString();
    }

    private static void ensureWrap(Cell cell){
        if(cell==null||cell.getCellStyle().getWrapText())return;
        Workbook wb=cell.getSheet().getWorkbook();
        CellStyle style=wb.createCellStyle();style.cloneStyleFrom(cell.getCellStyle());style.setWrapText(true);cell.setCellStyle(style);
    }

    private static void writeReplacedValue(Cell cell,String source,String replaced){
        Matcher whole=WHOLE_TOKEN.matcher(source==null?"":source);
        String numeric=replaced==null?"":replaced.replace(",","").trim();
        // Keep absent ERP values as true blank cells. Writing "" creates an empty
        // shared-string entry and some lightweight XLSX previewers display its internal
        // sharedStrings.xml index (for example 57/107) instead of a blank value.
        if (replaced == null || replaced.isBlank()) {
            cell.setBlank();
            return;
        }
        if(whole.matches() && isNumericExcelField(whole.group(1)) && numeric.matches("-?\\d+(\\.\\d+)?")){
            try{double value=Double.parseDouble(numeric);cell.setCellValue(value);if(requiresTwoDecimalMoneyFormat(whole.group(1)))ensureTwoDecimalMoneyFormat(cell);return;}catch (Exception ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
        }
        cell.setCellValue(replaced==null?"":replaced);
    }

    private static boolean isNumericExcelField(String key){
        if(key==null)return false;
        if(key.startsWith("item.")||key.startsWith("charge."))return true;
        if(key.matches("charge\\[\\d+]\\.(amount|gstPercent|taxAmount|total)"))return true;
        if(key.equals("tax.cgstRate")||key.equals("tax.sgstRate")||key.equals("tax.igstRate")||key.equals("tax.gstRate")||key.equals("tax.primaryAmount")||key.equals("tax.secondaryAmount"))return true;
        return key.startsWith("totals.") && !key.equals("totals.amountInWords");
    }

    private static boolean requiresTwoDecimalMoneyFormat(String key){
        if(key==null)return false;
        if(key.matches("charge\\[\\d+]\\.(amount|taxAmount|total)"))return true;
        if(key.equals("tax.primaryAmount")||key.equals("tax.secondaryAmount"))return true;
        return key.startsWith("totals.")&&!key.equals("totals.amountInWords");
    }

    private static void ensureTwoDecimalMoneyFormat(Cell cell){
        Workbook workbook=cell.getSheet().getWorkbook();
        CellStyle style=workbook.createCellStyle();style.cloneStyleFrom(cell.getCellStyle());
        style.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00;-#,##0.00;0.00"));
        cell.setCellStyle(style);
    }

    private static String descriptionWithRemarks(String description,String remarks){
        String d=safe(description).trim(),r=safe(remarks).trim();
        if(d.isBlank())return r;
        if(r.isBlank())return d;
        return d+"\n"+r;
    }

    private static String money(double value){return String.format(Locale.ROOT,"%.2f",value);} private static String number(double value){return Math.rint(value)==value?String.format(Locale.ROOT,"%.0f",value):String.format(Locale.ROOT,"%.2f",value);} private static String quantityNumber(double value){double q=DocumentCalculationEngine.quantity(value);return java.math.BigDecimal.valueOf(q).stripTrailingZeros().toPlainString();} private static String safe(String v){return v==null?"":v;}
    private static String rootMessage(Throwable e){Throwable r=e;while(r.getCause()!=null&&r.getCause()!=r)r=r.getCause();return r.getMessage()==null?r.getClass().getSimpleName():r.getMessage();}
}
