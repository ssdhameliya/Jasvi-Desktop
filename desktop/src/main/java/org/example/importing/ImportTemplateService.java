package org.example.importing;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Writes import workbooks (.xlsx, .xls, .csv) with multi-color mandatory/semantic headers; no JavaFX or controller state. */
public final class ImportTemplateService {

    public void write(Path target, String module, String appVersion) throws Exception {
        String lower = target.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csv")) {
            writeCsv(target, module);
            return;
        }
        if (lower.endsWith(".xls")) {
            writeXls(target, module, appVersion);
            return;
        }
        try (OutputStream output = Files.newOutputStream(target)) {
            writeToStream(output, module, appVersion);
        }
    }

    /**
     * Writes all 3 template formats (.xlsx, .xls, and .csv) next to each other using the base filename of {@code target}.
     */
    public List<Path> writeAllThreeFormats(Path target, String module, String appVersion) throws Exception {
        String fileName = target.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String baseName = dot > 0 ? fileName.substring(0, dot) : fileName;
        Path dir = target.getParent() != null ? target.getParent() : Path.of(".");

        Path xlsxPath = dir.resolve(baseName + ".xlsx");
        Path xlsPath = dir.resolve(baseName + ".xls");
        Path csvPath = dir.resolve(baseName + ".csv");

        try (OutputStream outXlsx = Files.newOutputStream(xlsxPath)) {
            writeToStream(outXlsx, module, appVersion);
        }
        writeXls(xlsPath, module, appVersion);
        writeCsv(csvPath, module);

        return List.of(xlsxPath, xlsPath, csvPath);
    }

    public static void writeXls(Path target, String module, String appVersion) throws Exception {
        try (OutputStream output = Files.newOutputStream(target);
             Workbook workbook = new HSSFWorkbook()) {
            populateWorkbook(workbook, module, appVersion);
            workbook.write(output);
        }
    }

    public static void writeCsv(Path target, String module) throws Exception {
        List<String> fields = ImportModuleRegistry.fields(module);
        Set<String> required = ImportModuleRegistry.requiredFields(module);
        StringBuilder sb = new StringBuilder();
        List<String> headerCells = new ArrayList<>();
        for (String field : fields) {
            headerCells.add(required.contains(field) ? field + " *" : field);
        }
        sb.append(String.join(",", headerCells)).append("\n");
        List<List<String>> exampleRows = getDynamicExampleRows(module);
        for (List<String> row : exampleRows) {
            List<String> escaped = new ArrayList<>();
            for (String val : row) {
                if (val == null) escaped.add("");
                else if (val.contains(",") || val.contains("\"") || val.contains("\n")) {
                    escaped.add("\"" + val.replace("\"", "\"\"") + "\"");
                } else {
                    escaped.add(val);
                }
            }
            sb.append(String.join(",", escaped)).append("\n");
        }
        Files.writeString(target, sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
    }

    public static void writeToStream(OutputStream output, String module, String appVersion) throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            populateWorkbook(workbook, module, appVersion);
            workbook.write(output);
        }
    }

    private static void populateWorkbook(Workbook workbook, String module, String appVersion) {
        List<String> fields = ImportModuleRegistry.fields(module);
        Set<String> required = ImportModuleRegistry.requiredFields(module);

        Sheet sheet = workbook.createSheet("Import Template");
        Sheet instructions = workbook.createSheet("Instructions");

        // 1. Multi-color Header Legend at the top of Instructions sheet
        CellStyle mandatoryLegendStyle = createHeaderStyle(workbook, IndexedColors.DARK_RED, IndexedColors.WHITE, true);
        CellStyle refLegendStyle = createHeaderStyle(workbook, IndexedColors.DARK_TEAL, IndexedColors.WHITE, true);
        CellStyle taxLegendStyle = createHeaderStyle(workbook, IndexedColors.GOLD, IndexedColors.BLACK, true);
        CellStyle chargeLegendStyle = createHeaderStyle(workbook, IndexedColors.ROYAL_BLUE, IndexedColors.WHITE, true);
        CellStyle optionalLegendStyle = createHeaderStyle(workbook, IndexedColors.BLUE_GREY, IndexedColors.WHITE, false);

        String[][] colorLegend = {
            {"[RED HEADER] MANDATORY (*)", "Required field — cannot be blank. Rows missing mandatory fields will be rejected during preflight validation."},
            {"[TEAL HEADER] REFERENCE / ID", "Optional identifier, UTR/reference, GSTIN, or HSN lookup code."},
            {"[AMBER/GOLD HEADER] TAX & AMOUNT", "Optional GST rate, tax split, discount, price, or running balance column."},
            {"[BLUE HEADER] CHARGES & ATTACHMENTS", "Optional additional charges (Freight, Packing, etc.) and attachment file paths."},
            {"[SLATE GRAY HEADER] OPTIONAL METADATA", "Optional descriptive notes, contact details, addresses, or status flags."}
        };
        CellStyle[] legendStyles = {mandatoryLegendStyle, refLegendStyle, taxLegendStyle, chargeLegendStyle, optionalLegendStyle};

        int nextRow = 0;
        for (int i = 0; i < colorLegend.length; i++) {
            Row lRow = instructions.createRow(nextRow++);
            Cell badgeCell = lRow.createCell(0);
            badgeCell.setCellValue(colorLegend[i][0]);
            badgeCell.setCellStyle(legendStyles[i]);
            lRow.createCell(1).setCellValue(colorLegend[i][1]);
        }

        nextRow++; // Blank separator row

        String[][] guidance = {
            {org.example.service.BrandingService.companyName() + " " + appVersion + " Import Template", "Keep identifier and header names unchanged (mandatory headers include '*')."},
            {"Recommended mode", "Update non-blank fields: blank spreadsheet cells preserve existing master data."},
            {"Create new only", "Existing identifiers are skipped; only new records are created."},
            {"Create or update", "Existing master records are replaced with supplied values."},
            {"Skip existing", "Existing identifiers are never changed."},
            {"Financial documents", "Existing posted Sales and Purchase invoices are always protected and skipped."},
            {"GST / IGST", "For Sales/Purchases use gst_type = GST for intra-state or IGST for inter-state. Enter gst_percent only; " + org.example.service.BrandingService.applicationName() + " calculates tax amounts from line values."},
            {"GST calculation", "GST is calculated as CGST + SGST (equal halves); IGST applies the full GST rate as IGST. Do not enter tax amounts manually."},
            {"Unlimited Sales & Purchase charges", "Sales and Purchases may use additional_charges with entries separated by semicolons. Each entry is Type|Amount|Taxable|GSTPercent, for example Freight|250|true|18;Packing|100|false|0."},
            {"Multiple attachments", "Use attachment_files for semicolon-separated file paths. Paths may be absolute or relative to the import workbook. The older attachment_file column remains supported."},
            {"Safe process", "Run Validate only first, review the preview and generated result report, then import."},
            {"Identifiers", ImportModuleRegistry.identifierGuidance(module)}
        };
        for (String[] item : guidance) {
            Row row = instructions.createRow(nextRow++);
            row.createCell(0).setCellValue(item[0]);
            row.createCell(1).setCellValue(item[1]);
        }

        nextRow++; // Blank separator row
        Row dictHeader = instructions.createRow(nextRow++);
        dictHeader.createCell(0).setCellValue("FIELD NAME");
        dictHeader.createCell(1).setCellValue("STATUS / COLOR GROUP");
        dictHeader.createCell(2).setCellValue("DATA TYPE");
        dictHeader.createCell(3).setCellValue("FORMAT / ALLOWED VALUES");
        dictHeader.createCell(4).setCellValue("WHAT TO DO IF BLANK");

        for (String field : fields) {
            boolean isReq = required.contains(field);
            Row r = instructions.createRow(nextRow++);
            Cell nameCell = r.createCell(0);
            nameCell.setCellValue(isReq ? field + " *" : field);
            nameCell.setCellStyle(getFieldHeaderStyle(workbook, field, isReq));
            r.createCell(1).setCellValue(isReq ? "MANDATORY (*) [RED]" : getOptionalColorGroupLabel(field));
            r.createCell(2).setCellValue(ImportModuleRegistry.dataType(field));
            r.createCell(3).setCellValue(getFieldFormatInfo(field));
            r.createCell(4).setCellValue(getFieldBlankInfo(field, isReq));
        }

        instructions.setColumnWidth(0, 34 * 256);
        instructions.setColumnWidth(1, 28 * 256);
        instructions.setColumnWidth(2, 16 * 256);
        instructions.setColumnWidth(3, 42 * 256);
        instructions.setColumnWidth(4, 58 * 256);

        Row header = sheet.createRow(0);
        header.setHeightInPoints(24f);
        Drawing<?> drawing = null;
        try {
            drawing = sheet.createDrawingPatriarch();
        } catch (Exception ignored) {}
        CreationHelper factory = workbook.getCreationHelper();

        for (int i = 0; i < fields.size(); i++) {
            String field = fields.get(i);
            boolean isReq = required.contains(field);
            Cell cell = header.createCell(i);
            cell.setCellValue(isReq ? field + " *" : field);
            cell.setCellStyle(getFieldHeaderStyle(workbook, field, isReq));
            sheet.setColumnWidth(i, Math.max(18, field.length() + 6) * 256);

            if (drawing != null) {
                try {
                    ClientAnchor anchor = factory.createClientAnchor();
                    anchor.setCol1(cell.getColumnIndex());
                    anchor.setCol2(cell.getColumnIndex() + 2);
                    anchor.setRow1(cell.getRowIndex());
                    anchor.setRow2(cell.getRowIndex() + 3);
                    Comment comment = drawing.createCellComment(anchor);
                    String commentText = getFieldComment(module, field, isReq);
                    comment.setString(factory.createRichTextString(commentText));
                    comment.setAuthor("Jasvi ERP");
                    cell.setCellComment(comment);
                } catch (Exception ignored) {}
            }
        }

        List<List<String>> exampleRows = getDynamicExampleRows(module);
        int lastSampleRow = 0;
        for (int sampleIndex = 0; sampleIndex < exampleRows.size(); sampleIndex++) {
            Row sample = sheet.createRow(sampleIndex + 1);
            List<String> examples = exampleRows.get(sampleIndex);
            for (int columnIndex = 0; columnIndex < Math.min(fields.size(), examples.size()); columnIndex++) {
                sample.createCell(columnIndex).setCellValue(examples.get(columnIndex));
            }
            lastSampleRow = sampleIndex + 1;
        }

        try {
            DataValidationHelper validationHelper = sheet.getDataValidationHelper();
            for (int col = 0; col < fields.size(); col++) {
                String f = fields.get(col).toLowerCase(Locale.ROOT);
                String[] listValues = null;
                if (f.equals("gst_percent") || f.equals("charge_1_gst_percent") || f.equals("charge_2_gst_percent")) {
                    listValues = new String[]{"0", "5", "12", "18", "28"};
                } else if (f.equals("gst_type")) {
                    listValues = new String[]{"GST", "IGST"};
                } else if (f.equals("direction")) {
                    listValues = new String[]{"CR", "DR"};
                } else if (f.equals("charge_1_type") || f.equals("charge_2_type")) {
                    listValues = new String[]{"Freight", "Packing", "Insurance", "Handling", "Loading", "Other"};
                } else if (f.equals("charge_1_taxable") || f.equals("charge_2_taxable") || f.equals("is_active")) {
                    listValues = new String[]{"true", "false"};
                }
                if (listValues != null) {
                    org.apache.poi.ss.util.CellRangeAddressList addressList = new org.apache.poi.ss.util.CellRangeAddressList(1, 1000, col, col);
                    DataValidationConstraint constraint = validationHelper.createExplicitListConstraint(listValues);
                    DataValidation validation = validationHelper.createValidation(constraint, addressList);
                    validation.setSuppressDropDownArrow(false);
                    validation.setShowErrorBox(true);
                    sheet.addValidationData(validation);
                }
            }
        } catch (Exception ignored) {}

        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(1, lastSampleRow), 0, fields.size() - 1));
    }

    private static String getOptionalColorGroupLabel(String field) {
        String f = field.toLowerCase(Locale.ROOT);
        if (f.equals("reference") || f.contains("gstin") || f.equals("hsn") || f.endsWith("_code") || f.endsWith("_no")) {
            return "OPTIONAL REFERENCE/ID [TEAL]";
        }
        if (f.contains("gst") || f.contains("tax") || f.equals("cgst") || f.equals("sgst") || f.equals("igst")
                || f.contains("amount") || f.contains("price") || f.contains("balance") || f.contains("discount") || f.contains("value")) {
            return "OPTIONAL TAX/AMOUNT [AMBER]";
        }
        if (f.contains("charge") || f.contains("attachment")) {
            return "OPTIONAL CHARGE/PROOF [BLUE]";
        }
        return "OPTIONAL METADATA [SLATE]";
    }

    public static String getFieldFormatInfo(String field) {
        String f = field.toLowerCase(Locale.ROOT);
        if (f.contains("date")) return "YYYY-MM-DD (e.g. 2026-11-01) or DD/MM/YYYY";
        if (f.contains("gst_percent") || f.equals("gst")) return "Standard GST rate (0, 5, 12, 18, 28)";
        if (f.equals("gst_type")) return "GST (Intra-state) or IGST (Inter-state)";
        if (f.equals("direction")) return "CR (Credit/Deposit) or DR (Debit/Withdrawal)";
        if (f.equals("unit")) return "Unit of measure (e.g. Nos, KGS, MTR, PCS)";
        if (f.equals("hsn")) return "6 or 8 digit HSN/SAC code (e.g. 73063000)";
        if (f.equals("email")) return "Valid email address (e.g. name@example.com)";
        if (f.equals("is_active") || f.contains("taxable")) return "true or false";
        if (f.equals("attachment_files")) return "Semicolon-separated file paths (inv.pdf;spec.pdf)";
        if (f.equals("additional_charges")) return "Type|Amount|Taxable|GSTPercent;... (Freight|250|true|18)";
        if (f.contains("amount") || f.contains("price") || f.contains("rate") || f.contains("quantity") || f.contains("value")) {
            return "Positive decimal number (e.g. 1500.00)";
        }
        return "Alphanumeric Text";
    }

    public static String getFieldBlankInfo(String field, boolean isRequired) {
        if (isRequired) return "Mandatory. Cannot be blank; will fail validation if empty.";
        String f = field.toLowerCase(Locale.ROOT);
        if (f.contains("gst_percent") || f.equals("gst")) return "If blank, default 18% is applied.";
        if (f.equals("gst_type")) return "If blank, default 'GST' is applied.";
        if (f.equals("is_active")) return "If blank, default 'true' is applied.";
        if (f.equals("balance")) return "If blank, balance is computed from ledger.";
        return "Optional. Leave blank to preserve defaults or existing record data.";
    }

    public static String getFieldComment(String module, String field, boolean isRequired) {
        String format = getFieldFormatInfo(field);
        String blank = getFieldBlankInfo(field, isRequired);
        return (isRequired ? "[MANDATORY *] Required field. " : "[" + getOptionalColorGroupLabel(field) + "] ") + blank + " Format: " + format;
    }

    public static CellStyle getFieldHeaderStyle(Workbook workbook, String field) {
        return getFieldHeaderStyle(workbook, field, false);
    }

    /**
     * 5-Color Semantic & Mandatory Header Palette:
     * 1. RED (DARK_RED / WHITE bold): Strictly when isRequired == true (Mandatory *)
     * 2. TEAL (DARK_TEAL / WHITE bold): Optional Reference, UTR, GSTIN, HSN, or Code
     * 3. AMBER (GOLD / BLACK bold): Optional Tax, GST, Price, Discount, or Balance
     * 4. BLUE (ROYAL_BLUE / WHITE bold): Optional Charges & Attachments
     * 5. SLATE (BLUE_GREY / WHITE): Optional General Metadata
     */
    public static CellStyle getFieldHeaderStyle(Workbook workbook, String field, boolean isRequired) {
        if (isRequired) {
            return createHeaderStyle(workbook, IndexedColors.DARK_RED, IndexedColors.WHITE, true);
        }
        String f = field.toLowerCase(Locale.ROOT);
        if (f.equals("reference") || f.contains("gstin") || f.equals("hsn") || f.endsWith("_code") || f.endsWith("_no")) {
            return createHeaderStyle(workbook, IndexedColors.DARK_TEAL, IndexedColors.WHITE, true);
        }
        if (f.contains("gst") || f.equals("taxable_value") || f.equals("cgst") || f.equals("sgst") || f.equals("igst")
                || f.equals("invoice_value") || f.contains("amount") || f.contains("price") || f.contains("balance") || f.contains("discount")) {
            return createHeaderStyle(workbook, IndexedColors.GOLD, IndexedColors.BLACK, true);
        }
        if (f.contains("charge") || f.contains("attachment")) {
            return createHeaderStyle(workbook, IndexedColors.ROYAL_BLUE, IndexedColors.WHITE, true);
        }
        return createHeaderStyle(workbook, IndexedColors.BLUE_GREY, IndexedColors.WHITE, false);
    }

    public static CellStyle createHeaderStyle(Workbook workbook) {
        return createHeaderStyle(workbook, IndexedColors.ROYAL_BLUE, IndexedColors.WHITE, true);
    }

    public static CellStyle createHeaderStyle(Workbook workbook, IndexedColors bg, IndexedColors fg, boolean bold) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(bold);
        font.setColor(fg.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(bg.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private static List<List<String>> getDynamicExampleRows(String module) {
        List<List<String>> rows = ImportModuleRegistry.exampleRows(module);
        try {
            java.util.Map<String, String> formats = new org.example.api.master.MasterApiClient().referenceFormats();
            String format = null;
            if ("Sales".equalsIgnoreCase(module)) {
                format = formats.get("REF_SALES");
            } else if ("Purchases".equalsIgnoreCase(module)) {
                format = formats.get("REF_PURCHASE");
            } else if ("Customers/CRM".equalsIgnoreCase(module)) {
                format = formats.get("REF_CUSTOMER");
            } else if ("Suppliers/HRM".equalsIgnoreCase(module)) {
                format = formats.get("REF_SUPPLIER");
            } else if (module != null && (module.contains("Item") || module.equalsIgnoreCase("Inventory"))) {
                format = formats.get("REF_ITEM");
            }

            java.time.LocalDate today = java.time.LocalDate.now();
            List<List<String>> dynamicRows = new java.util.ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                List<String> original = rows.get(i);
                List<String> updated = new java.util.ArrayList<>(original);
                if (format != null && !format.isBlank() && !updated.isEmpty()) {
                    String sampleRef = org.example.shared.ReferenceFormatRules.format(format, i + 1, today);
                    updated.set(0, sampleRef);
                }
                if ("Sales".equalsIgnoreCase(module) && updated.size() > 3) {
                    String cusFmt = formats.get("REF_CUSTOMER");
                    if (cusFmt != null && !cusFmt.isBlank()) {
                        updated.set(2, org.example.shared.ReferenceFormatRules.format(cusFmt, i + 1, today));
                    }
                    String itmFmt = formats.get("REF_ITEM");
                    if (itmFmt != null && !itmFmt.isBlank()) {
                        updated.set(3, org.example.shared.ReferenceFormatRules.format(itmFmt, 1, today));
                    }
                }
                if ("Purchases".equalsIgnoreCase(module) && updated.size() > 3) {
                    String supFmt = formats.get("REF_SUPPLIER");
                    if (supFmt != null && !supFmt.isBlank()) {
                        updated.set(2, org.example.shared.ReferenceFormatRules.format(supFmt, i + 1, today));
                    }
                    String itmFmt = formats.get("REF_ITEM");
                    if (itmFmt != null && !itmFmt.isBlank()) {
                        updated.set(3, org.example.shared.ReferenceFormatRules.format(itmFmt, 1, today));
                    }
                }
                dynamicRows.add(java.util.List.copyOf(updated));
            }
            return java.util.List.copyOf(dynamicRows);
        } catch (Exception ignored) {
        }
        return rows;
    }
}
