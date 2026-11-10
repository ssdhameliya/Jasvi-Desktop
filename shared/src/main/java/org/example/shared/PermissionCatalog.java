package org.example.shared;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared presentation metadata for the permission catalog. The database remains the
 * source of truth for which permission keys exist; unknown future modules still
 * render automatically using the fallback metadata below.
 */
public final class PermissionCatalog {
    public static final List<String> COMMON_ACTIONS = List.of("VIEW", "CREATE", "EDIT", "DELETE", "EXPORT", "APPROVE");

    public record ModuleMeta(String key, String label, String category, String iconKey, int order) {}
    public record Template(String key, String label, String description) {
        @Override public String toString() { return label; }
    }

    public static final Template READ_ONLY = new Template("READ_ONLY", "Read Only", "Grant view access without change permissions");
    public static final Template SALES_STANDARD = new Template("SALES_STANDARD", "Sales Standard", "Sales, quotation and customer workflows with safe day-to-day actions");
    public static final Template MANAGER_STANDARD = new Template("MANAGER_STANDARD", "Manager Standard", "Broad business access without protected administration actions");
    public static final Template BUSINESS_FULL = new Template("BUSINESS_FULL", "Business Full Access", "All business capabilities while protected administration stays separate");
    public static final List<Template> TEMPLATES = List.of(READ_ONLY, SALES_STANDARD, MANAGER_STANDARD, BUSINESS_FULL);

    private static final Map<String, ModuleMeta> MODULES = Map.ofEntries(
            entry("DASHBOARD", "Dashboard", "Dashboard & Insights", "dashboard", 10),
            entry("SALES", "Sales Register", "Sales", "sales", 20),
            entry("QUOTATION", "Quotation", "Sales", "quotation", 21),
            entry("CUSTOMERS", "Customers", "Sales", "customer", 22),
            entry("COMMUNICATION", "Communication", "Sales", "email", 23),
            entry("REMINDERS", "Reminders", "Sales", "reminder", 24),
            entry("CREDIT_NOTE", "Credit Notes", "Sales", "credit_note", 25),
            entry("PURCHASE", "Purchase Register", "Purchase", "purchase", 30),
            entry("SUPPLIERS", "Suppliers", "Purchase", "supplier", 31),
            entry("DEBIT_NOTE", "Debit Notes", "Purchase", "debit_note", 32),
            entry("INVENTORY", "Inventory / Item Master", "Inventory", "inventory", 40),
            entry("IMPORT", "Data Import", "Inventory", "import", 41),
            entry("BARCODE", "Barcode Studio", "Inventory", "barcode", 42),
            entry("BANK_EXPENSE", "Finance & Banking", "Finance & Banking", "bank", 50),
            entry("GST_COMPLIANCE", "GST Compliance", "Finance & Compliance", "tax", 52),
            entry("EWAY_BILL", "E-Way Bill & E-Invoice", "Finance & Compliance", "eway", 53),
            entry("PURCHASE_RECON", "Purchase Recon (2B)", "Finance & Compliance", "reconcile", 54),
            entry("RECON_SUPPLIER", "Recon Suppliers", "Finance & Compliance", "supplier", 55),
            entry("GENERAL_LEDGER", "General Ledger & Accounts", "Finance & Compliance", "ledger", 56),
            entry("FINANCIAL_STATEMENTS", "Financial Statements (P&L / BS)", "Finance & Compliance", "statement", 57),
            entry("AGING_ANALYSIS", "AR / AP Aging Analysis", "Finance & Compliance", "aging", 58),
            entry("TALLY", "Bill to Tally Automation", "Finance & Compliance", "ledger", 59),
            entry("MASTERS", "Masters", "Masters", "master", 60),
            entry("REPORTS", "Reports", "Reports", "report", 70),
            entry("DOCUMENT_STUDIO", "Document Studio", "Document Studio", "document", 80),
            entry("USERS", "User Access", "Administration", "users", 90),
            entry("SETTINGS", "Settings", "Administration", "settings", 91),
            entry("BACKUP", "Backup & Restore", "Administration", "backup", 92),
            entry("APPLICATION_UPDATES", "Application Updates", "Administration", "update", 93),
            entry("SAFE_ROLLBACK", "Safe Rollback", "Administration", "rollback", 94),
            entry("AUTOMATION", "Automation Center", "Administration", "automation", 95),
            entry("AUDIT", "Global Audit Trail", "Administration", "audit", 96),
            entry("MULTI_BRANCH", "Multi-Branch Support", "Enterprise & Sync", "master", 97),
            entry("OFFLINE_SYNC", "Offline P2P Sync", "Enterprise & Sync", "update", 98)
    );

    private static final Set<String> ALWAYS_ACCESSIBLE_MODULES = Set.of(
            "DASHBOARD", "SETTINGS", "USERS", "APPLICATION_UPDATES", "SAFE_ROLLBACK", "AUDIT"
    );

    private static final Set<String> CORE_FINANCE_MODULES = Set.of(
            "DASHBOARD", "SALES", "QUOTATION", "CUSTOMERS", "CREDIT_NOTE", "REMINDERS",
            "PURCHASE", "SUPPLIERS", "DEBIT_NOTE", "INVENTORY", "BARCODE",
            "BANK_EXPENSE", "GST_COMPLIANCE", "GENERAL_LEDGER", "FINANCIAL_STATEMENTS",
            "AGING_ANALYSIS", "MASTERS", "REPORTS"
    );

    private static final Set<String> SALES_MODULES = Set.of(
            "DASHBOARD", "SALES", "QUOTATION", "CUSTOMERS", "COMMUNICATION", "REMINDERS", "REPORTS", "INVENTORY", "CREDIT_NOTE");
    private static final Set<String> ADMINISTRATION_MODULES = Set.of(
            "USERS", "SETTINGS", "BACKUP", "APPLICATION_UPDATES", "SAFE_ROLLBACK", "AUTOMATION", "AUDIT");

    private PermissionCatalog() {}

    public static ModuleMeta module(String key) {
        String normalized = normalize(key);
        ModuleMeta known = MODULES.get(normalized);
        if (known != null) return known;
        String label = title(normalized.replace('_', ' '));
        return new ModuleMeta(normalized, label, "Other", "permission", 999);
    }

    /**
     * Returns all licensable ERP modules sorted by display order for Super Admin plan & client entitlement toggles.
     */
    public static List<ModuleMeta> subscriptionModules() {
        return MODULES.values().stream()
                .filter(m -> !ALWAYS_ACCESSIBLE_MODULES.contains(m.key()))
                .sorted(java.util.Comparator.comparingInt(ModuleMeta::order).thenComparing(ModuleMeta::label))
                .toList();
    }

    public static boolean isAlwaysAccessibleModule(String moduleKey) {
        return ALWAYS_ACCESSIBLE_MODULES.contains(normalize(moduleKey));
    }

    /**
     * Expands both granular module keys (e.g. SALES, PURCHASE, TALLY) and legacy macro bundle tags
     * (e.g. CORE_FINANCE, PRINT_EXPORT, WHATSAPP_ROUTING, TALLY_SYNC) into a canonical set of active tokens.
     */
    public static Set<String> expandFeatureTokens(java.util.Collection<String> rawTokens) {
        if (rawTokens == null || rawTokens.isEmpty()) return Set.of();
        java.util.Set<String> expanded = new java.util.LinkedHashSet<>();
        for (String raw : rawTokens) {
            if (raw == null || raw.isBlank()) continue;
            for (String part : raw.split("[,;\\s]+")) {
                String token = normalize(part.replace("\"", "").replace("'", ""));
                if (token.isEmpty() || "NULL".equals(token)) continue;
                expanded.add(token);
                switch (token) {
                    case "ALL", "*" -> {
                        MODULES.keySet().forEach(expanded::add);
                        expanded.addAll(List.of("CORE_FINANCE", "PRINT_EXPORT", "DATA_IMPORTER", "EWAY_BILL",
                                "CLOUD_BACKUP", "WHATSAPP_ROUTING", "BANK_RECON", "TALLY_SYNC", "MULTI_BRANCH", "OFFLINE_SYNC"));
                    }
                    case "CORE_FINANCE", "GST_INVOICE" -> expanded.addAll(CORE_FINANCE_MODULES);
                    case "PRINT_EXPORT" -> expanded.add("DOCUMENT_STUDIO");
                    case "DOCUMENT_STUDIO" -> expanded.add("PRINT_EXPORT");
                    case "DATA_IMPORTER" -> expanded.add("IMPORT");
                    case "IMPORT" -> expanded.add("DATA_IMPORTER");
                    case "WHATSAPP_ROUTING" -> expanded.add("COMMUNICATION");
                    case "COMMUNICATION" -> expanded.add("WHATSAPP_ROUTING");
                    case "BANK_RECON" -> {
                        expanded.add("PURCHASE_RECON");
                        expanded.add("RECON_SUPPLIER");
                    }
                    case "PURCHASE_RECON", "RECON_SUPPLIER" -> expanded.add("BANK_RECON");
                    case "TALLY_SYNC" -> {
                        expanded.add("TALLY");
                        expanded.add("AUTOMATION");
                    }
                    case "TALLY" -> expanded.add("TALLY_SYNC");
                    case "CLOUD_BACKUP" -> expanded.add("BACKUP");
                    case "BACKUP" -> expanded.add("CLOUD_BACKUP");
                    case "EWAYBILL", "EWAY" -> expanded.add("EWAY_BILL");
                    default -> {}
                }
            }
        }
        expanded.addAll(ALWAYS_ACCESSIBLE_MODULES);
        return Set.copyOf(expanded);
    }

    public static boolean isCommonAction(String action) {
        return COMMON_ACTIONS.contains(normalize(action));
    }

    public static String actionLabel(String action) {
        String normalized = normalize(action);
        return switch (normalized) {
            case "EXPORT_PDF" -> "Export PDF";
            case "MANAGE_TEMPLATES" -> "Manage Templates";
            case "MANAGE_ROLES" -> "Manage Roles";
            case "MANAGE_PERMISSIONS" -> "Manage Permissions";
            case "GLOBAL" -> "Global Audit";
            case "RECONCILE" -> "Reconcile";
            case "MATCH" -> "Match";
            case "EXECUTE" -> "Execute";
            case "POST" -> "Post";
            case "GENERATE" -> "Generate";
            case "UPDATE_VEHICLE" -> "Update Vehicle";
            case "REMIND" -> "Send Reminder";
            default -> title(normalized.replace('_', ' '));
        };
    }

    public static boolean templateAllows(Template template, String module, String action) {
        if (template == null) return false;
        String m = normalize(module);
        String a = normalize(action);
        return switch (template.key()) {
            case "READ_ONLY" -> !ADMINISTRATION_MODULES.contains(m) && "VIEW".equals(a);
            case "SALES_STANDARD" -> salesTemplate(m, a);
            case "MANAGER_STANDARD" -> managerTemplate(m, a);
            case "BUSINESS_FULL" -> !ADMINISTRATION_MODULES.contains(m);
            default -> false;
        };
    }

    private static boolean salesTemplate(String module, String action) {
        if (!SALES_MODULES.contains(module)) return false;
        if ("DASHBOARD".equals(module) || "REPORTS".equals(module) || "INVENTORY".equals(module)) {
            return "VIEW".equals(action) || "EXPORT".equals(action);
        }
        if ("REMINDERS".equals(module)) {
            return Set.of("VIEW", "CREATE", "EDIT", "COMPLETE", "SNOOZE").contains(action);
        }
        if ("COMMUNICATION".equals(module)) {
            return Set.of("VIEW", "CREATE", "EDIT", "RESEND").contains(action);
        }
        return Set.of("VIEW", "CREATE", "EDIT", "EXPORT").contains(action);
    }

    private static boolean managerTemplate(String module, String action) {
        if (ADMINISTRATION_MODULES.contains(module)) return false;
        if ("DOCUMENT_STUDIO".equals(module)) {
            return Set.of("VIEW", "CREATE", "EDIT", "EXPORT_PDF", "MANAGE_TEMPLATES").contains(action);
        }
        if ("BANK_EXPENSE".equals(module)) {
            return Set.of("VIEW", "CREATE", "EDIT", "EXPORT", "RECONCILE", "APPROVE").contains(action);
        }
        if ("GST_COMPLIANCE".equals(module)) {
            return Set.of("VIEW", "EXPORT", "RECONCILE").contains(action);
        }
        if ("GENERAL_LEDGER".equals(module)) {
            return Set.of("VIEW", "CREATE", "POST", "EXPORT").contains(action);
        }
        return !"DELETE".equals(action) || Set.of("SALES", "PURCHASE", "QUOTATION", "CUSTOMERS", "SUPPLIERS", "INVENTORY", "MASTERS").contains(module);
    }

    public static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static Map.Entry<String, ModuleMeta> entry(String key, String label, String category, String icon, int order) {
        return Map.entry(key, new ModuleMeta(key, label, category, icon, order));
    }

    private static String title(String value) {
        if (value == null || value.isBlank()) return "";
        StringBuilder out = new StringBuilder();
        for (String part : value.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (part.isBlank()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }
}
