package org.example.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Universal Operational Guidance & Screen Information Registry.
 * Provides authentic, structured documentation, bold green recommendations,
 * and bold red critical alerts across all Jasvi ERP screens and settings sub-menus.
 */
public final class ScreenGuidanceRegistry {

    private static final Map<String, ScreenGuidance> REGISTRY = new HashMap<>();

    static {
        registerSettingsGuidance();
        registerScreenGuidance();
    }

    private ScreenGuidanceRegistry() {}

    public static ScreenGuidance get(String screenKey) {
        if (screenKey == null) return null;
        return REGISTRY.get(screenKey.toUpperCase());
    }

    public static Map<String, ScreenGuidance> all() {
        return Collections.unmodifiableMap(REGISTRY);
    }

    private static void register(ScreenGuidance guidance) {
        REGISTRY.put(guidance.screenKey().toUpperCase(), guidance);
    }

    private static void registerSettingsGuidance() {
        // 1. Company Settings
        register(new ScreenGuidance(
            "SETTINGS_COMPANY",
            "Company Profile & Branding Settings",
            "company",
            "Configure legal enterprise identity, GSTIN credentials, primary contact details, and corporate branding assets.",
            List.of(
                "Verify and maintain official registered company name, address, phone, and statutory GSTIN.",
                "Upload high-resolution corporate logo and authorized signature stamp for all printed documents.",
                "Configure regional date format, financial year cycle, and business currency conventions."
            ),
            List.of(
                "Document branding synchronizes automatically across Sales Invoices, Purchase Bills, and Quotations.",
                "GSTIN state code prefix automatically establishes the home state for Interstate (IGST) vs Intrastate tax determination.",
                "Setting the correct financial year boundary ensures sequential fiscal document series generation."
            ),
            List.of(
                "Changing Legal Company Name or GSTIN alters official legal documents; verify tax certificates before saving.",
                "Do not replace the primary signature asset without authorized enterprise administrative clearance."
            ),
            List.of("Ctrl + S : Save Settings", "Esc : Cancel Changes")
        ));

        // 2. Payment Settings
        register(new ScreenGuidance(
            "SETTINGS_PAYMENT",
            "Payment & Banking Configuration",
            "payment",
            "Manage payment gateway accounts, UPI/GPay dynamic link generation, default receipt ledgers, and cash roundoff rules.",
            List.of(
                "Link primary bank accounts and cash drawers to the universal chart of accounts.",
                "Configure UPI VPA ID and merchant name for instant dynamic QR code printing on customer tax bills.",
                "Set payment grace periods, default credit terms (Net 15, Net 30), and overdue interest flags."
            ),
            List.of(
                "Dynamic UPI QR codes allow retail and counter clients to scan and pay directly from smartphones.",
                "Enabling roundoff safety keeps invoice totals aligned to the nearest Indian Rupee automatically.",
                "Linking bank account codes streamlines automated bank statement reconciliation."
            ),
            List.of(
                "Changing primary bank account numbers will impact invoice printouts immediately.",
                "Never disable cash rounding if your GST filing relies on whole-rupee invoice tax accounting."
            ),
            List.of("Ctrl + S : Save Settings")
        ));

        // 3. Invoice Settings
        register(new ScreenGuidance(
            "SETTINGS_INVOICE",
            "Invoice Formatting & Numbering Rules",
            "invoice",
            "Define document series numbering prefixes, terms & conditions, default dispatch templates, and GST print options.",
            List.of(
                "Configure custom prefixes for Sales (INV-), Purchases (PUR-), Quotations (QTN-), and Credit Notes (CRN-).",
                "Define custom payment terms, delivery notes, and jurisdiction declarations printed on invoice footers.",
                "Select default print engine templates (Compact Retail, Corporate B2B, Export Modern)."
            ),
            List.of(
                "Continuous automated numbering guarantees zero missing sequence gaps during statutory GST audits.",
                "Including HSN summary tables on invoice templates is mandatory for B2B taxpayers with turnover above 5 Crores.",
                "Configuring vehicle and transport defaults accelerates E-Way bill dispatch generation."
            ),
            List.of(
                "NEVER reset invoice starting serial numbers in the middle of an active financial year.",
                "Altering invoice prefix format mid-year will cause sequence mismatches on the government GST portal."
            ),
            List.of("Ctrl + S : Save Settings")
        ));

        // 4. Notifications Settings
        register(new ScreenGuidance(
            "SETTINGS_NOTIFICATIONS",
            "Notification & Alert Preferences",
            "bell",
            "Control real-time workstation toast popups, sound chimes, notification drawer retention, and audit triggers.",
            List.of(
                "Toggle desktop notifications for low inventory stock, overdue customer invoices, and bank match events.",
                "Set notification history retention periods (30 days, 90 days, or indefinite audit retention).",
                "Configure audio alerts for fast-paced barcode scanning and counter billing transactions."
            ),
            List.of(
                "Desktop alerts alert billing staff immediately when an inventory item drops below reorder threshold.",
                "Notification badges highlight pending approval requests across Sales Returns and Purchase Orders."
            ),
            List.of(
                "Critical Security and Failed Payment notifications cannot be disabled to ensure compliance.",
                "Setting retention to less than 30 days purges non-critical user audit trails permanently."
            ),
            List.of("Ctrl + S : Save Settings")
        ));

        // 5. Email Delivery Settings
        register(new ScreenGuidance(
            "SETTINGS_EMAIL",
            "Email Gateway & Corporate SMTP",
            "email",
            "Configure outbound SMTP email delivery, SSL/TLS encryption, sender identity, and automated PDF delivery templates.",
            List.of(
                "Provide SMTP server host, port (465 SSL or 587 STARTTLS), authentication username, and app password.",
                "Customize standard email subject lines and body text for invoices, quotes, and payment receipts.",
                "Click 'Test Email' to send an instant diagnostic test message to verify credentials."
            ),
            List.of(
                "Using dedicated app-specific passwords (e.g. Google Workspace, Microsoft 365) prevents credential lockouts.",
                "Automated PDF attachment embedding ensures customers receive formatted tax documents instantly on dispatch."
            ),
            List.of(
                "Incorrect SMTP port or encryption settings will block customer invoice and payment receipt delivery.",
                "Never share or commit plain text email account passwords in unencrypted configuration backups."
            ),
            List.of("Ctrl + T : Test Email", "Ctrl + S : Save Settings")
        ));

        // 6. Security Settings
        register(new ScreenGuidance(
            "SETTINGS_SECURITY",
            "Security, MFA & Session Policies",
            "security",
            "Enforce workstation inactivity timeouts, Multi-Factor Authentication (MFA) policies, and role-based permissions.",
            List.of(
                "Configure workstation inactivity lock timeout (e.g. 5, 10, or 15 minutes) with graceful countdown warning.",
                "Enforce Time-based One-Time Password (TOTP) authenticator apps for administrative users.",
                "Control multi-session login concurrency and password complexity rules."
            ),
            List.of(
                "Inactivity locking safeguards open accounting terminals when billing operators step away from workstations.",
                "TOTP authenticator app verification provides bank-grade protection against unauthorized login attempts."
            ),
            List.of(
                "Disabling Mandatory MFA for administrators increases exposure to credential theft and unauthorized access.",
                "Workstation locks immediately require the user's password to resume; keep credentials secure."
            ),
            List.of("Ctrl + S : Save Settings")
        ));

        // 7. Workspace Settings
        register(new ScreenGuidance(
            "SETTINGS_WORKSPACE",
            "Workspace Storage & Environment Mode",
            "workspace",
            "Manage local storage directories (D:\\DSE Production), cloud environment mode (UAT vs PROD), and file limits.",
            List.of(
                "Inspect local active workspace path, document attachment directories, and export repository folders.",
                "Verify database connection host, active port (55432), and Spring Boot backend communication gateway.",
                "Export full diagnostic system bundle for technical support and troubleshooting."
            ),
            List.of(
                "All reports and templates save systematically under D:\\DSE Production\\Exports\\ with zero OS dialog prompts.",
                "Workspace storage manager validates available disk space before every large export or backup."
            ),
            List.of(
                "CRITICAL: Switching Environment from UAT to PROD connects to the live production database; do not alter without authorization.",
                "Changing the storage base directory without migrating existing attachments will orphan document links."
            ),
            List.of("Ctrl + S : Save Settings")
        ));

        // 8. Keyboard Shortcuts Settings
        register(new ScreenGuidance(
            "SETTINGS_SHORTCUTS",
            "Keyboard Shortcuts & Productivity",
            "keyboard",
            "Customize global hotkeys, register accelerators, and focus commands for high-speed, mouse-free ERP operation.",
            List.of(
                "Review default shortcuts (Ctrl+N: New, Ctrl+F: Quick Search, F5: Refresh, Ctrl+S: Save, Ctrl+P: Print).",
                "Assign custom key combinations to frequent operations and screen navigation actions.",
                "Configure shortcut scope (Global application-wide vs Screen-focused action)."
            ),
            List.of(
                "Mastering keyboard shortcuts accelerates counter billing and invoice creation by over 300%.",
                "Esc key is universally mapped across all dialogs and search boxes to dismiss safely."
            ),
            List.of(
                "Avoid remapping common operating system keys (such as Ctrl+C, Ctrl+V, Alt+Tab) to prevent input conflicts.",
                "Conflicting shortcut combinations will display a warning and must be resolved before saving."
            ),
            List.of("Ctrl + S : Save Settings")
        ));

        // 9. Updates Settings
        register(new ScreenGuidance(
            "SETTINGS_UPDATES",
            "Application Updates & Version Control",
            "update",
            "Manage software update channels, verified SHA-256 binary downloads, version rollbacks, and release changelogs.",
            List.of(
                "Check for published application updates via the secure Spring Boot server gateway.",
                "Review verified release changelog, new features, bug fixes, and database migration notes.",
                "Access Safe Rollback center to revert to a verified historical release if necessary."
            ),
            List.of(
                "All application updates are SHA-256 checksum verified prior to execution to prevent binary tampering.",
                "Updates install gracefully in the background without interrupting active database connections."
            ),
            List.of(
                "Never force-close the application while an update package is being extracted and applied.",
                "Database migrations executed during an update are irreversible; always maintain verified daily backups."
            ),
            List.of("Ctrl + U : Check Updates")
        ));

        // 10. Modules Settings
        register(new ScreenGuidance(
            "SETTINGS_MODULES",
            "Feature Modules & System Engines",
            "modules",
            "Enable or disable modular ERP subsystems, statutory calculation engines, and advanced automated workflows.",
            List.of(
                "Toggle modules: Bank Reconciliation, E-Way Bill Center, GST Compliance, Barcode Studio, Document Studio.",
                "Enable advanced calculation engines: Auto-Place of Supply, Roundoff Safety, Multi-Currency.",
                "Configure automated synchronization intervals for client portals and background schedulers."
            ),
            List.of(
                "Disabling unused modules simplifies sidebar navigation and accelerates screen loading speeds.",
                "Core statutory engines ensure 100% compliance with government Goods and Services Tax regulations."
            ),
            List.of(
                "Disabling an active module does not delete its database records, but hides its menus and screens from users.",
                "Ensure dependent transactions are settled before disabling secondary billing or inventory modules."
            ),
            List.of("Ctrl + S : Save Settings")
        ));

        // 11. Showcase Settings
        register(new ScreenGuidance(
            "SETTINGS_SHOWCASE",
            "Dynamic Public Showcase & RFQ Settings",
            "globe",
            "Configure synchronization between Jasvi-Desktop and the public UserView 3D web showcase portal.",
            List.of(
                "Link machinery catalog products to the public showcase website (jasviindustries.in).",
                "Configure cloud API endpoints for receiving customer Request for Quotation (RFQ) inquiries in real time.",
                "Manage published specifications, 3D model references, and promotional pricing visibility."
            ),
            List.of(
                "Customer RFQs submitted on the website appear directly in Quotation Register for instant sales follow-up.",
                "Item master specifications sync seamlessly to web showcase without requiring manual duplicate entry."
            ),
            List.of(
                "Products marked as 'Published' become immediately visible to public website visitors.",
                "Verify machinery technical specifications and pricing before initiating public showcase sync."
            ),
            List.of("Ctrl + S : Save Settings")
        ));
    }

    private static void registerScreenGuidance() {
        // 1. Data Import
        register(new ScreenGuidance(
            "DATA_IMPORT",
            "Data Import & Migration Wizard",
            "import",
            "Bulk import master entities (Items, Customers, Suppliers) and transactional records (Sales, Purchases, Bank CSV) from Excel workbooks.",
            List.of(
                "Step 1: Choose the destination module card and download the official template (.xlsx, .xls, or .csv).",
                "Step 2: Fill records into the downloaded template without modifying the header column names.",
                "Step 3: Drag & drop the completed file into the upload zone or click 'Browse File'.",
                "Step 4: Review automatic column mapping, inspect data preview rows, and click 'Validate only' for a safe dry-run.",
                "Step 5: Click 'Import Records' to commit validated rows into the PostgreSQL enterprise database."
            ),
            List.of(
                "Download templates in your preferred format (.xlsx, .xls, .csv, or ALL 3) directly to workspace without folder prompts.",
                "Auto Map automatically pairs workbook headers to ERP fields using intelligent synonym matching.",
                "Preflight validation inspects data types, duplicate primary keys, and required foreign references before database insertion."
            ),
            List.of(
                "DO NOT rename, reorder, or delete columns in downloaded templates to prevent mapping discrepancies.",
                "Always run 'Validate only (do not save)' dry-run first when importing thousands of historical rows.",
                "Imported records immediately affect financial balances and inventory stock valuations."
            ),
            List.of("Ctrl + O : Browse Import File", "F5 : Refresh Wizard")
        ));

        // 2. Sales Register
        register(new ScreenGuidance(
            "SALES_REGISTER",
            "Sales Invoices & Billing Register",
            "sale",
            "Comprehensive management of customer tax invoices, B2B/B2C billings, payment tracking, dispatches, and credit terms.",
            List.of(
                "Create new GST-compliant sales invoices with automatic tax rate calculation (IGST vs CGST+SGST).",
                "Search in real time with 300ms debouncing across invoice number, customer name, phone, or GSTIN.",
                "Filter by Date Ranges (Today, Yesterday, 7 Days, This FY), Payment Status (Paid, Partial, Due), and Document Status.",
                "Export filtered registers to Excel or branded PDF automatically to D:\\DSE Production\\Exports\\Sales\\.",
                "Inspect full 360° invoice details, print tax invoices, and initiate WhatsApp/Email transmission."
            ),
            List.of(
                "Customer GSTIN state prefix automatically establishes Interstate vs Intrastate tax classification.",
                "Sales returns linked to an invoice automatically reduce customer outstanding balances in real time.",
                "All exported workbooks and PDFs save in the background and open the interactive post-export feedback card."
            ),
            List.of(
                "NEVER delete an approved or GST-filed invoice; use 'Credit Note / Return' or 'Cancel' to maintain legal audit continuity.",
                "Invoices with status 'RETURN APPROVAL PENDING' cannot be edited until the return request is settled."
            ),
            List.of("Ctrl + N : New Invoice", "Ctrl + F : Quick Search", "Ctrl + P : Print Register", "F5 : Refresh")
        ));

        // 3. Purchase Register
        register(new ScreenGuidance(
            "PURCHASE_REGISTER",
            "Purchase Bills & Inward Register",
            "purchase",
            "Track vendor bills, raw material receipts, input tax credit (ITC) claims, and supplier payment balances.",
            List.of(
                "Record vendor tax invoices with automatic inventory stock addition and item batch cost tracking.",
                "Filter bills by vendor, payment status, fiscal date ranges, and reconciliation match status.",
                "Verify GSTR-2B input tax credit eligibility against government portal data.",
                "Export purchase register to Excel or PDF with one click."
            ),
            List.of(
                "Entering accurate vendor invoice numbers and dates is essential for automated GSTR-2B reconciliation.",
                "Input tax credit (ITC) claims are tracked in real time against the general ledger."
            ),
            List.of(
                "Cancelling a purchase invoice reverses added inventory stock; ensure physical materials are not already consumed.",
                "Verify vendor GSTIN status before logging bills to prevent ITC reversals during tax filing."
            ),
            List.of("Ctrl + N : New Purchase", "Ctrl + F : Search Bills", "F5 : Refresh")
        ));

        // 4. Bank Statement Workspace
        register(new ScreenGuidance(
            "BANK_STATEMENT",
            "Bank Statement & Auto-Reconciliation",
            "bank",
            "Universal multi-bank statement parsing, automated 1-to-1 ledger matching, fuzzy synonym rules, and reconciliation audits.",
            List.of(
                "Click 'Import Statement' to parse statement files (.xlsx, .xls, .csv) with automatic header detection.",
                "System auto-detects known bank formats (Kotak, HDFC, ICICI, SBI, Axis) and maps dates, narration, debits, and credits.",
                "Review matched transactions, confirm paired invoice payments, and identify un-reconciled bank charges."
            ),
            List.of(
                "Original bank statement files and raw CSV content are preserved immutably with SHA-256 hash fingerprints.",
                "Auto-matching pairs customer receipts and vendor payouts based on exact reference numbers and amounts."
            ),
            List.of(
                "Never modify bank transaction values directly; bank evidence is legally binding and retained for audits.",
                "Ignored transactions remain visible in audit logs with mandatory exclusion reasons."
            ),
            List.of("Ctrl + I : Import Statement", "F5 : Refresh Workspace")
        ));

        // 5. Bill To Tally
        register(new ScreenGuidance(
            "BILL_TO_TALLY",
            "Bill to Tally Automation Gateway",
            "tally",
            "AI-powered OCR and blueprint extraction of vendor bills into XML and Excel formats ready for direct Tally import.",
            List.of(
                "Upload or drag & drop scanned vendor invoices, PDFs, and spreadsheet bill formats.",
                "Review automated OCR data extraction, mathematical verification, and item breakdown accuracy.",
                "Click 'Export to Tally' to generate native Tally XML or structured JSON payloads."
            ),
            List.of(
                "Accuracy verification ensures zero math discrepancies between line items, taxes, and total invoice amounts.",
                "Custom client blueprint mappings remember vendor invoice formats for 100% automated future extraction."
            ),
            List.of(
                "Bills with accuracy percentage below 90% must be reviewed manually before exporting to Tally.",
                "Ensure vendor ledgers exist in Tally with matching names to prevent voucher import errors."
            ),
            List.of("Ctrl + U : Upload Bills", "Ctrl + E : Export Tally XML")
        ));

        // 6. GST Compliance Center
        register(new ScreenGuidance(
            "GST_COMPLIANCE",
            "GST Compliance & Government E-Filing",
            "gst",
            "Automated Goods & Services Tax calculation, GSTR-1 government JSON generation, GSTR-3B tax summaries, and ITC audits.",
            List.of(
                "Generate GSTR-1 summaries categorized by B2B, B2CL, B2CS, CDNR, Export, and HSN tables.",
                "Audit GSTR-3B monthly tax liabilities (Output Tax minus Eligible ITC equals Net Tax Payable).",
                "Export government-compliant JSON files for direct upload to the official GST Portal."
            ),
            List.of(
                "Place of Supply logic automatically validates State Codes, CGST+SGST, and IGST breakdowns.",
                "HSN summary tables calculate 4-digit and 6-digit codes with total quantities and taxable amounts."
            ),
            List.of(
                "CRITICAL: Filing periods lock automatically; transactions dated in locked periods cannot be altered without filing revisions.",
                "Verify customer GSTIN validation flags prior to generating monthly government JSON exports."
            ),
            List.of("Ctrl + G : Generate Summary", "Ctrl + E : Export JSON")
        ));

        // 7. General Ledger
        register(new ScreenGuidance(
            "GENERAL_LEDGER",
            "General Ledger & Chart of Accounts",
            "ledger",
            "Double-entry bookkeeping, account statement inspection, journal vouchers, debit/credit audit trails, and running balances.",
            List.of(
                "Select any asset, liability, equity, revenue, or expense account to view its complete historical ledger.",
                "Filter journal entries by fiscal date ranges, voucher types, and reference keywords.",
                "Export complete account ledgers to Excel or branded PDF reports."
            ),
            List.of(
                "Every business transaction in Sales, Purchase, and Banking posts verified double-entry journal vouchers in real time.",
                "Running balance calculations maintain strict accounting equation integrity (Assets = Liabilities + Equity)."
            ),
            List.of(
                "Direct manual journal vouchers must be balanced (Total Debits must equal Total Credits) before saving.",
                "Historical ledger entries cannot be deleted; adjusting entries must be posted to preserve audit integrity."
            ),
            List.of("Ctrl + F : Search Accounts", "Ctrl + P : Print Ledger")
        ));

        // 8. Item Master
        register(new ScreenGuidance(
            "ITEM_MASTER",
            "Item Master & Inventory Catalog",
            "item",
            "Manage product inventory, stock SKUs, HSN/SAC codes, tax rates, batch tracking, reorder thresholds, and pricing tiers.",
            List.of(
                "Add and edit product items with unit measures (PCS, KGS, MTR, BOX), sales price, and cost price.",
                "Assign statutory GST tax rates (0%, 5%, 12%, 18%, 28%) and HSN codes for automatic billing lookup.",
                "Set minimum reorder stock thresholds and warehouse storage locations."
            ),
            List.of(
                "Item barcodes scan seamlessly in billing windows for instant high-speed counter dispatch.",
                "Price changes take effect immediately on new quotes and invoices without altering historical records."
            ),
            List.of(
                "Do not change the HSN code or tax percentage of an item mid-quarter without consulting your tax advisor.",
                "Items with active stock balance cannot be deleted; use 'Archive' or 'Deactivate' instead."
            ),
            List.of("Ctrl + N : New Item", "Ctrl + F : Search Items", "F5 : Refresh")
        ));

        // 9. Customer Master
        register(new ScreenGuidance(
            "CUSTOMER_MASTER",
            "Customer Directory & CRM Workspace",
            "customer",
            "Manage customer profiles, B2B billing addresses, GSTIN credentials, credit limits, contact persons, and aging ledgers.",
            List.of(
                "Create B2B customer accounts with verified GSTIN, state code, PAN, and delivery shipping addresses.",
                "Configure customer-specific credit limits, payment terms, and price discount tiers.",
                "Access Customer 360° view to review entire sales history, payment receipts, and communications."
            ),
            List.of(
                "Accurate customer GSTIN ensures seamless input tax credit for your B2B clients.",
                "Customer outstanding balances update automatically upon invoice generation and payment receipt logging."
            ),
            List.of(
                "Do not alter the GSTIN of an existing customer if historical invoices have already been filed on the GST portal.",
                "Exceeding configured credit limits prompts an administrative override alert during invoice creation."
            ),
            List.of("Ctrl + N : New Customer", "Ctrl + F : Search Customers")
        ));

        // 10. Backup & Restore
        register(new ScreenGuidance(
            "BACKUP_RESTORE",
            "PostgreSQL Database Backup & Disaster Recovery",
            "database",
            "Create verified local database snapshots, schedule automated daily backups, and execute safety-checked system restores.",
            List.of(
                "Click 'Create Backup' to generate an instantaneous PostgreSQL binary dump (.pgbackup).",
                "Inspect historical backups with SHA-256 integrity checksums, file sizes, and record counts.",
                "Verify pre-restore database health before executing application recovery."
            ),
            List.of(
                "All backups include database tables, sequences, schema migrations, and active user credentials.",
                "Backups are verified for integrity immediately after generation to ensure recoverability."
            ),
            List.of(
                "CRITICAL: Restoring a database snapshot completely overwrites current runtime data with snapshot state.",
                "A safety backup is created automatically before any restore operation is allowed to commence.",
                "Never store backup archives exclusively on the local machine; maintain secondary off-site backups."
            ),
            List.of("Ctrl + B : Create Backup", "F5 : Refresh List")
        ));

        // 11. Client Template Builder
        register(new ScreenGuidance(
            "CLIENT_TEMPLATE",
            "Invoice Template Builder & Blueprint Versioning",
            "document",
            "Define document headers and item-level column blueprints for accurate OCR extraction and Tally voucher generation.",
            List.of(
                "Upload sample vendor bills (.pdf, .png, .xlsx) to inspect layout coordinates and auto-detect header labels.",
                "Map document headers (Invoice No, Date, Vendor GSTIN, Taxable, GST amounts) and verify confidence scores.",
                "Configure item-level table columns (Description, HSN, Quantity, Unit, Rate, Amount) with 1-to-1 canonical bindings.",
                "Lock and save template versions to guarantee zero-drift extraction across future invoice batches."
            ),
            List.of(
                "Locked template versions are immutable; creating edits automatically provisions a new incremental version.",
                "Regex pattern matching and fuzzy column detection allow slight layout deviations to parse accurately."
            ),
            List.of(
                "Never delete a locked template version that is actively bound to historical Tally export batches.",
                "Mandatory canonical fields (Invoice Number, Invoice Date, Total Amount) must be mapped before locking a version."
            ),
            List.of("Ctrl + S : Lock & Save Template", "Esc : Back to Client Directory")
        ));
    }
}
