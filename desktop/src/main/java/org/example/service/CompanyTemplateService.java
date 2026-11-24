package org.example.service;

import org.example.api.documents.CompanyTemplateApiClient;
import org.example.documentstudio.model.DocumentType;
import org.example.documentstudio.model.ExcelTemplate;
import org.example.documentstudio.model.TemplateStatus;
import org.example.documentstudio.service.ExcelTemplateStorageService;

import java.util.*;

public final class CompanyTemplateService {

    public record TemplateOption(String code, String name, String operationType, String description, String badge) {
        public String displayName() {
            if (badge != null && !badge.isBlank()) {
                return name + " [" + badge + "]";
            }
            return name;
        }
    }

    private static final CompanyTemplateApiClient API = new CompanyTemplateApiClient();

    private CompanyTemplateService() {}

    public static List<TemplateOption> getPdfCatalog(String operation) {
        List<Map<String, Object>> raw = API.getCatalog(operation);
        if (raw == null || raw.isEmpty()) {
            return fallbackPdfCatalog(operation);
        }
        List<TemplateOption> list = new ArrayList<>();
        for (var m : raw) {
            String code = String.valueOf(m.getOrDefault("code", ""));
            String name = String.valueOf(m.getOrDefault("name", code));
            String op = String.valueOf(m.getOrDefault("operationType", "SALES"));
            String desc = String.valueOf(m.getOrDefault("description", ""));
            String badge = code.startsWith("Jasvi_") ? "Core Master" : "PDF Layout";
            list.add(new TemplateOption(code, name, op, desc, badge));
        }
        return list;
    }

    public static List<TemplateOption> getExcelCatalog(String operation) {
        List<TemplateOption> list = new ArrayList<>();
        String op = operation == null ? "SALES" : operation.trim().toUpperCase(Locale.ROOT);

        // 1. Fetch live templates from Excel Studio
        try {
            List<ExcelTemplate> studioTemplates = ExcelTemplateStorageService.listAll();
            for (ExcelTemplate t : studioTemplates) {
                if (t.getStatus() != TemplateStatus.ACTIVE && !t.isDefaultTemplate()) continue;
                if (matchesOperation(t.getDocumentType(), op)) {
                    String badge = "v" + String.format("%02d", t.getVersion()) + " • " + t.getStatus().name();
                    String desc = "Excel Studio template: " + t.getName() + " (" + t.getSourceFile() + ")";
                    list.add(new TemplateOption(t.getName(), t.getName(), op, desc, badge));
                }
            }
        } catch (Exception ex) {
            // Excel Studio access failure fallback
        }

        // 2. Add built-in defaults and standards if not already present
        List<TemplateOption> defaults = fallbackExcelCatalog(op);
        for (var d : defaults) {
            boolean exists = list.stream().anyMatch(e -> e.code().equalsIgnoreCase(d.code()));
            if (!exists) {
                list.add(d);
            }
        }

        return list;
    }

    public static List<TemplateOption> getCatalog(String operation) {
        return getPdfCatalog(operation);
    }

    private static boolean matchesOperation(DocumentType type, String operation) {
        if (type == null || operation == null) return false;
        return switch (operation.toUpperCase(Locale.ROOT)) {
            case "SALES" -> type == DocumentType.SALES_INVOICE;
            case "PURCHASE" -> type == DocumentType.PURCHASE_INVOICE || type == DocumentType.PURCHASE_ORDER;
            case "QUOTATION" -> type == DocumentType.QUOTATION;
            case "RETURNS" -> type == DocumentType.SALES_RETURN || type == DocumentType.PURCHASE_RETURN
                    || type == DocumentType.CREDIT_NOTE || type == DocumentType.DEBIT_NOTE;
            default -> false;
        };
    }

    public static Map<String, String> getCompanyMappings(Long companyId) {
        List<Map<String, Object>> raw = API.getCompanyMappings(companyId);
        Map<String, String> map = new LinkedHashMap<>();
        // Set standard base defaults
        map.put("SALES_PDF", "Jasvi_Sale");
        map.put("SALES_EXCEL", "Sale Invoice 1");
        map.put("PURCHASE_PDF", "Jasvi_Purchase");
        map.put("PURCHASE_EXCEL", "BuiltIn_Purchase_Excel");
        map.put("QUOTATION_PDF", "Jasvi_Quotation");
        map.put("QUOTATION_EXCEL", "BuiltIn_Quotation_Excel");
        map.put("RETURNS_PDF", "Jasvi_Return");
        map.put("RETURNS_EXCEL", "New Purchase Return Excel");

        if (raw != null) {
            for (var m : raw) {
                String op = String.valueOf(m.getOrDefault("operationType", ""));
                String code = String.valueOf(m.getOrDefault("templateCode", ""));
                if (!op.isBlank() && !code.isBlank()) {
                    String normOp = op.toUpperCase(Locale.ROOT);
                    map.put(normOp, code);
                    // Also populate base if only base was saved previously
                    if (normOp.equals("SALES") && !map.containsKey("SALES_PDF")) map.put("SALES_PDF", code);
                    if (normOp.equals("PURCHASE") && !map.containsKey("PURCHASE_PDF")) map.put("PURCHASE_PDF", code);
                    if (normOp.equals("QUOTATION") && !map.containsKey("QUOTATION_PDF")) map.put("QUOTATION_PDF", code);
                    if (normOp.equals("RETURNS") && !map.containsKey("RETURNS_PDF")) map.put("RETURNS_PDF", code);
                }
            }
        }
        return map;
    }

    public static boolean saveCompanyMappings(Long companyId, Map<String, String> mappings) {
        // Synchronize base keys with PDF keys for backward compatibility
        Map<String, String> payload = new LinkedHashMap<>(mappings);
        if (payload.containsKey("SALES_PDF")) payload.put("SALES", payload.get("SALES_PDF"));
        if (payload.containsKey("PURCHASE_PDF")) payload.put("PURCHASE", payload.get("PURCHASE_PDF"));
        if (payload.containsKey("QUOTATION_PDF")) payload.put("QUOTATION", payload.get("QUOTATION_PDF"));
        if (payload.containsKey("RETURNS_PDF")) payload.put("RETURNS", payload.get("RETURNS_PDF"));
        return API.saveCompanyMappings(companyId, payload);
    }

    public static String resolveTemplate(Long companyId, String operation) {
        return API.resolveTemplate(companyId, operation);
    }

    private static List<TemplateOption> fallbackPdfCatalog(String operation) {
        List<TemplateOption> all = List.of(
                new TemplateOption("Jasvi_Sale", "Jasvi Flagship GST Tax Invoice", "SALES", "Flagship standard GST tax invoice with full statutory tax schedule and banking", "Core Master"),
                new TemplateOption("Standard_Sale_Modern", "Contemporary Corporate Layout", "SALES", "Full-width modern header banner with clean grid typography", "Standard"),
                new TemplateOption("Standard_Sale_Compact", "High-Density Spare Parts Grid", "SALES", "Dense layout for parts, hardware, and high item counts", "Standard"),
                new TemplateOption("Standard_Sale_Thermal", "3-Inch POS Counter Receipt", "SALES", "80mm compact slip format for retail counters", "Standard"),
                new TemplateOption("Standard_Sale_Export", "Export Commercial Invoice", "SALES", "Export compliant invoice with LUT/Bond declaration and foreign currency", "Standard"),

                new TemplateOption("Jasvi_Purchase", "Jasvi Standard Purchase Order", "PURCHASE", "Official procurement purchase order with delivery instructions and schedule", "Core Master"),
                new TemplateOption("Standard_Purchase_Compact", "High-Density Goods Receipt PO", "PURCHASE", "Compact purchase bill optimized for raw material intake", "Standard"),

                new TemplateOption("Jasvi_Quotation", "Jasvi Machinery Commercial Proposal", "QUOTATION", "Detailed machinery quotation with warranty clauses, validity, and commercial terms", "Core Master"),
                new TemplateOption("Standard_Quotation_Estimate", "Quick Industrial Price Estimate", "QUOTATION", "Concise commercial estimate format with standard delivery terms", "Standard"),

                new TemplateOption("Jasvi_Return", "Jasvi GST Credit & Debit Note", "RETURNS", "Statutory GST credit/debit adjustment note cross-referenced to original invoice", "Core Master")
        );
        if (operation == null || operation.isBlank()) return all;
        return all.stream().filter(t -> t.operationType().equalsIgnoreCase(operation.trim())).toList();
    }

    private static List<TemplateOption> fallbackExcelCatalog(String operation) {
        List<TemplateOption> all = List.of(
                new TemplateOption("Sale Invoice 1", "Sale Invoice 1", "SALES", "Excel Studio default Sales Invoice workbook", "Excel Studio"),
                new TemplateOption("BuiltIn_Sales_Excel", "Jasvi Built-In Sales Excel", "SALES", "Standard system Excel invoice export with statutory tax summary", "Built-In"),

                new TemplateOption("BuiltIn_Purchase_Excel", "Jasvi Built-In Purchase Excel", "PURCHASE", "Standard procurement Excel intake workbook", "Built-In"),

                new TemplateOption("BuiltIn_Quotation_Excel", "Jasvi Built-In Quotation Excel", "QUOTATION", "Standard commercial estimate Excel workbook", "Built-In"),

                new TemplateOption("New Purchase Return Excel", "New Purchase Return Excel", "RETURNS", "Excel Studio default Return Register workbook", "Excel Studio"),
                new TemplateOption("BuiltIn_Return_Excel", "Jasvi Built-In Return Excel", "RETURNS", "Standard GST credit/debit adjustment Excel workbook", "Built-In")
        );
        if (operation == null || operation.isBlank()) return all;
        return all.stream().filter(t -> t.operationType().equalsIgnoreCase(operation.trim())).toList();
    }
}
