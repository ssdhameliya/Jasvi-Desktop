package org.example.server.master;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.shared.ReferenceFormatRules;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class TemplateExportService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    public byte[] generateTemplate(String module, Map<String, String> formats) throws Exception {
        String mod = normalizeModule(module);
        List<String> fields = fieldsFor(mod);
        List<List<String>> rows = sampleRowsFor(mod, formats);

        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Import Template");
            Sheet instructions = workbook.createSheet("Instructions");

            // Instructions Sheet
            String[][] guidance = {
                {"Jasvi ERP Import Template (" + mod + ")", "Keep identifier and header names unchanged."},
                {"Header Color Coding", "[RED] Mandatory Required Fields | [AMBER/YELLOW] Tax & GST Fields | [BLUE] Charge Columns | [GRAY] Optional Details"},
                {"In-Cell Excel Dropdowns", "Columns like gst_percent, gst_type, charge types, and taxable flags provide native Excel dropdown menus."},
                {"Recommended Mode", "Update non-blank fields: blank spreadsheet cells preserve existing master data."},
                {"Financial Documents", "Existing posted Sales and Purchase invoices are always protected and skipped."},
                {"GST / IGST", "For Sales/Purchases use gst_type = GST for intra-state or IGST for inter-state. Enter gst_percent only; ERP calculates tax amounts from line values."},
                {"GST Calculation", "GST is calculated as CGST + SGST (equal halves); IGST applies the full GST rate as IGST. Do not enter tax amounts manually."},
                {"Document Charges", "Sales & Purchases support charge columns (charge_1_type, charge_1_amount, etc.) and unlimited additional_charges (Type|Amount|Taxable|GSTPercent;...)."},
                {"Multiple Attachments", "Use attachment_files for semicolon-separated file paths (e.g. invoice.pdf;delivery-receipt.pdf)."},
                {"Safe Process", "Run Validate only first, review the preview and generated result report, then import."}
            };
            for (int i = 0; i < guidance.length; i++) {
                Row row = instructions.createRow(i);
                row.createCell(0).setCellValue(guidance[i][0]);
                row.createCell(1).setCellValue(guidance[i][1]);
            }
            instructions.setColumnWidth(0, 32 * 256);
            instructions.setColumnWidth(1, 95 * 256);

            // Header Row
            Row header = sheet.createRow(0);
            for (int i = 0; i < fields.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(fields.get(i));
                cell.setCellStyle(getHeaderStyle(workbook, fields.get(i)));
                sheet.setColumnWidth(i, Math.max(14, fields.get(i).length() + 3) * 256);
            }

            // Sample Data Rows
            int lastSampleRow = 0;
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<String> rowData = rows.get(r);
                for (int c = 0; c < Math.min(fields.size(), rowData.size()); c++) {
                    row.createCell(c).setCellValue(rowData.get(c));
                }
                lastSampleRow = r + 1;
            }

            // In-Cell Excel Dropdown Data Validations
            DataValidationHelper validationHelper = sheet.getDataValidationHelper();
            for (int col = 0; col < fields.size(); col++) {
                String f = fields.get(col).toLowerCase(Locale.ROOT);
                String[] listValues = null;
                if (f.equals("gst_percent") || f.equals("charge_1_gst_percent") || f.equals("charge_2_gst_percent")) {
                    listValues = new String[]{"0", "5", "12", "18", "28"};
                } else if (f.equals("gst_type")) {
                    listValues = new String[]{"GST", "IGST"};
                } else if (f.equals("charge_1_type") || f.equals("charge_2_type")) {
                    listValues = new String[]{"Freight", "Packing", "Insurance", "Handling", "Loading", "Other"};
                } else if (f.equals("charge_1_taxable") || f.equals("charge_2_taxable") || f.equals("is_active")) {
                    listValues = new String[]{"true", "false"};
                }
                if (listValues != null) {
                    CellRangeAddressList addressList = new CellRangeAddressList(1, 1000, col, col);
                    DataValidationConstraint constraint = validationHelper.createExplicitListConstraint(listValues);
                    DataValidation validation = validationHelper.createValidation(constraint, addressList);
                    validation.setSuppressDropDownArrow(true);
                    validation.setShowErrorBox(true);
                    sheet.addValidationData(validation);
                }
            }

            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new CellRangeAddress(0, Math.max(1, lastSampleRow), 0, fields.size() - 1));

            workbook.write(out);
            return out.toByteArray();
        }
    }

    private String normalizeModule(String raw) {
        if (raw == null) return "Sales";
        String s = raw.trim();
        if (s.equalsIgnoreCase("Sales") || s.equalsIgnoreCase("Sale")) return "Sales";
        if (s.equalsIgnoreCase("Purchases") || s.equalsIgnoreCase("Purchase")) return "Purchases";
        if (s.toLowerCase(Locale.ROOT).contains("customer")) return "Customers";
        if (s.toLowerCase(Locale.ROOT).contains("supplier")) return "Suppliers";
        if (s.toLowerCase(Locale.ROOT).contains("item") || s.toLowerCase(Locale.ROOT).contains("inventory")) return "Items";
        if (s.toLowerCase(Locale.ROOT).contains("recon")) return "Purchase Recon";
        if (s.toLowerCase(Locale.ROOT).contains("bank")) return "Bank Statements";
        return "Sales";
    }

    private List<String> fieldsFor(String module) {
        return switch (module) {
            case "Sales", "Purchases" -> List.of(
                "invoice_no", "invoice_date", "party_code", "item_code", "quantity", "rate", "gst_percent", "gst_type",
                "payment_terms", "paid_amount", "remarks",
                "charge_1_type", "charge_1_amount", "charge_1_taxable", "charge_1_gst_percent",
                "charge_2_type", "charge_2_amount", "charge_2_taxable", "charge_2_gst_percent",
                "additional_charges", "attachment_files"
            );
            case "Customers", "Suppliers" -> List.of(
                "party_code", "name", "contact_person", "phone", "email", "gstin", "address", "opening_balance", "is_active"
            );
            case "Items" -> List.of(
                "item_code", "name", "description", "category", "unit", "hsn_code", "purchase_rate", "sale_rate", "gst_percent", "min_stock", "opening_stock", "is_active"
            );
            case "Purchase Recon" -> List.of(
                "supplier_name", "supplier_gstin", "supplier_invoice_no", "invoice_date", "taxable_value", "cgst", "sgst", "igst", "invoice_value"
            );
            case "Bank Statements" -> List.of(
                "transaction_date", "value_date", "description", "reference", "amount", "direction", "balance"
            );
            default -> List.of("party_code", "name", "is_active");
        };
    }

    private List<List<String>> sampleRowsFor(String module, Map<String, String> formats) {
        LocalDate today = LocalDate.now();
        String dateStr = today.format(DATE_FMT);
        Map<String, String> fmts = formats != null ? formats : Map.of();

        String cusFormat = fmts.getOrDefault("REF_CUSTOMER", "CUSXXX");
        String supFormat = fmts.getOrDefault("REF_SUPPLIER", "SUPXXX");
        String itmFormat = fmts.getOrDefault("REF_ITEM", "ITMXXX");
        String salFormat = fmts.getOrDefault("REF_SALES", "IN/DD-MM-YYYY/XXXX");
        String purFormat = fmts.getOrDefault("REF_PURCHASE", "PUR/DD-MM-YYYY/XXXX");

        if ("Sales".equals(module)) {
            String inv1 = ReferenceFormatRules.format(salFormat, 1, today);
            String inv2 = ReferenceFormatRules.format(salFormat, 2, today);
            String cus1 = ReferenceFormatRules.format(cusFormat, 1, today);
            String cus2 = ReferenceFormatRules.format(cusFormat, 2, today);
            String itm1 = ReferenceFormatRules.format(itmFormat, 1, today);

            return List.of(
                List.of(inv1, dateStr, cus1, itm1, "2", "1500", "18", "GST", "15 Days", "0", "Sample intra-state sale with optional charges", "Freight", "250", "true", "18", "Packing", "100", "false", "0", "Insurance|75|true|18;Handling|50|false|0", ""),
                List.of(inv2, dateStr, cus2, itm1, "1", "2000", "18", "IGST", "15 Days", "0", "Sample inter-state sale; multiple attachments optional", "", "", "", "", "", "", "", "", "Freight|250|true|18", "invoice.pdf;delivery-receipt.pdf")
            );
        }

        if ("Purchases".equals(module)) {
            String pur1 = ReferenceFormatRules.format(purFormat, 1, today);
            String pur2 = ReferenceFormatRules.format(purFormat, 2, today);
            String sup1 = ReferenceFormatRules.format(supFormat, 1, today);
            String sup2 = ReferenceFormatRules.format(supFormat, 2, today);
            String itm1 = ReferenceFormatRules.format(itmFormat, 1, today);

            return List.of(
                List.of(pur1, dateStr, sup1, itm1, "10", "1200", "18", "GST", "15 Days", "0", "Sample intra-state purchase with unlimited charge syntax", "Freight", "250", "true", "18", "Packing", "100", "false", "0", "Insurance|75|true|18;Handling|50|false|0", ""),
                List.of(pur2, dateStr, sup2, itm1, "5", "1200", "18", "IGST", "15 Days", "0", "Sample inter-state purchase; multiple attachments are optional", "", "", "", "", "", "", "", "", "Freight|250|true|18", "invoice.pdf;quality-certificate.pdf")
            );
        }

        if ("Customers".equals(module)) {
            String cus1 = ReferenceFormatRules.format(cusFormat, 1, today);
            return List.of(
                List.of(cus1, "ABC Enterprises", "Ravi Patel", "9876543210", "accounts@example.com", "24AAAAA1111A1Z5", "Ahmedabad, Gujarat", "0", "true")
            );
        }

        if ("Suppliers".equals(module)) {
            String sup1 = ReferenceFormatRules.format(supFormat, 1, today);
            return List.of(
                List.of(sup1, "Steel Supplier Ltd", "Amit Shah", "9876500000", "sales@supplier.example", "24BBBBB2222B1Z4", "Rajkot, Gujarat", "0", "true")
            );
        }

        if ("Items".equals(module)) {
            String itm1 = ReferenceFormatRules.format(itmFormat, 1, today);
            return List.of(
                List.of(itm1, "Industrial Flange 50mm", "Forged stainless steel 50mm flange", "RAW_MATERIAL", "Nos", "7307", "850.00", "1100.00", "18", "25", "100", "true")
            );
        }

        return List.of();
    }

    private CellStyle getHeaderStyle(Workbook workbook, String field) {
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

    private CellStyle createHeaderStyle(Workbook workbook, IndexedColors bg, IndexedColors fg, boolean bold) {
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
}
