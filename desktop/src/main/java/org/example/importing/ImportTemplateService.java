package org.example.importing;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Writes import workbooks; no JavaFX or controller state. */
public final class ImportTemplateService {
    public void write(Path target, String module, String appVersion) throws Exception {
        List<String> fields = ImportModuleRegistry.fields(module);
        try (Workbook workbook = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(target)) {
            Sheet sheet = workbook.createSheet("Import Template");
            Sheet instructions = workbook.createSheet("Instructions");
            String[][] guidance = {
                {org.example.service.BrandingService.companyName() + " " + appVersion + " Import Template", "Keep identifier and header names unchanged."},
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
                {"Identifiers", ImportModuleRegistry.identifierGuidance(module)},
                {"Header Color Coding", "[RED] Mandatory Required Fields | [AMBER/YELLOW] Tax & GST Fields | [BLUE] Charge Columns | [GRAY] Optional Details"}
            };
            for (int i = 0; i < guidance.length; i++) {
                Row row = instructions.createRow(i);
                row.createCell(0).setCellValue(guidance[i][0]);
                row.createCell(1).setCellValue(guidance[i][1]);
            }
            instructions.setColumnWidth(0, 32 * 256);
            instructions.setColumnWidth(1, 95 * 256);

            Row header = sheet.createRow(0);
            for (int i = 0; i < fields.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(fields.get(i));
                cell.setCellStyle(getFieldHeaderStyle(workbook, fields.get(i)));
                sheet.setColumnWidth(i, Math.max(14, fields.get(i).length() + 3) * 256);
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
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(1, lastSampleRow), 0, fields.size() - 1));
            workbook.write(output);
        }
    }

    public static CellStyle getFieldHeaderStyle(Workbook workbook, String field) {
        String f = field.toLowerCase(Locale.ROOT);
        if (f.equals("invoice_no") || f.equals("invoice_date") || f.equals("party_code") || f.equals("item_code") ||
            f.equals("quantity") || f.equals("rate") || f.equals("name") || f.equals("description") || f.equals("category_code") || f.equals("value_code")) {
            return createHeaderStyle(workbook, IndexedColors.DARK_RED, IndexedColors.WHITE, true);
        } else if (f.contains("gst") || f.equals("taxable_value") || f.equals("cgst") || f.equals("sgst") || f.equals("igst") || f.equals("invoice_value")) {
            return createHeaderStyle(workbook, IndexedColors.DARK_YELLOW, IndexedColors.BLACK, true);
        } else if (f.contains("charge")) {
            return createHeaderStyle(workbook, IndexedColors.ROYAL_BLUE, IndexedColors.WHITE, true);
        } else {
            return createHeaderStyle(workbook, IndexedColors.GREY_50_PERCENT, IndexedColors.WHITE, false);
        }
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
            if (format != null && !format.isBlank()) {
                java.time.LocalDate today = java.time.LocalDate.now();
                List<List<String>> dynamicRows = new java.util.ArrayList<>();
                for (int i = 0; i < rows.size(); i++) {
                    List<String> original = rows.get(i);
                    List<String> updated = new java.util.ArrayList<>(original);
                    if (!updated.isEmpty()) {
                        String sampleRef = org.example.shared.ReferenceFormatRules.format(format, i + 1, today);
                        updated.set(0, sampleRef);
                    }
                    dynamicRows.add(java.util.List.copyOf(updated));
                }
                return java.util.List.copyOf(dynamicRows);
            }
        } catch (Exception ignored) {
            // Graceful fallback to static examples if server lookup is unavailable
        }
        return rows;
    }
}
