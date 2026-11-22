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

        // 5b. WhatsApp & WAHA Integration Settings
        register(new ScreenGuidance(
            "SETTINGS_WHATSAPP",
            "WhatsApp Business Integration (WAHA)",
            "message",
            "Connect automated document dispatch, multi-number WAHA Docker engines, and instant WhatsApp PDF delivery.",
            List.of(
                "Configure WAHA Docker endpoint (default http://localhost:3000 or remote cloud server URL).",
                "Scan the WhatsApp Web QR code using your official company WhatsApp number to establish a persistent session.",
                "Map specialized WhatsApp sender numbers for Sales Invoices, Purchase Orders, Quotations, and Payment Reminders.",
                "Enable 'Enable Server Automated WhatsApp Dispatch' to trigger instant customer messages upon invoice creation."
            ),
            List.of(
                "WAHA runs as a secure local Docker sidecar, keeping all company communication direct without third-party message fees.",
                "Automated PDF document dispatch sends official invoices with dynamic payment status links directly to customer mobile numbers.",
                "Multi-department channel routing ensures sales queries and purchasing dispatches operate through distinct corporate lines."
            ),
            List.of(
                "Ensure WAHA Docker container remains running; if stopped, outgoing customer notifications will queue until restarted.",
                "Adhere to WhatsApp Business Anti-Spam Guidelines: only dispatch transactional documents to verified business contacts."
            ),
            List.of("Ctrl + S : Save WhatsApp Settings", "Esc : Back")
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

        // 10. Modules Settings (Master Directory)
        register(new ScreenGuidance(
            "SETTINGS_MODULES",
            "ERP Feature Modules & Automated Subsystems",
            "modules",
            "Master activation center for ERP business subsystems, statutory calculation engines, and autonomous operational automations. Deactivated modules are hidden from navigation with zero runtime overhead.",
            List.of(
                "1. General Ledger: Double-entry journal vouchers, live Trial Balance, and real-time financial statements.",
                "2. GST Statutory Engine: Automated Place of Supply tax determination, GSTR-1 JSON export, and GSTR-2B ITC fuzzy matching.",
                "3. Operational Automations: 3-way purchase matching, auto debit notes, safety stock reorder alerts, and customer overdue credit hard stops.",
                "4. Executive Briefing: Autonomous 8:00 PM evening business summary broadcast via WhatsApp (WAHA) and corporate Email (SMTP).",
                "5. Warehouse & Batch/Serial: Multi-depot stock partitioning, First-Expired First-Out (FEFO) picking, and serial warranty tracking.",
                "6. Collections Dispatch: 1-click WhatsApp/Email overdue reminders with auto-generated Branded Party Statement PDFs.",
                "7. Bank Narration Rules: Smart pattern learning for recurring statement transactions to accelerate bank reconciliation."
            ),
            List.of(
                "✓ Granular Guidance: Click the circular (ℹ) guidance button on any module card header for detailed flag breakdowns and real-world business examples.",
                "✓ Performance Isolation: Deactivated modules bypass background polling and event listeners with zero system overhead.",
                "✓ Safe State Retention: Toggling modules off preserves all historical database records intact in PostgreSQL."
            ),
            List.of(
                "⚠️ Gating & Safeguards: Ensure all open invoices and vouchers are saved before reconfiguring core accounting or tax modules.",
                "⚠️ Administrative Permission: Only authorized System Administrators can modify module activation flags."
            ),
            List.of("Ctrl + S : Save Module Configuration")
        ));

        // 10a. General Ledger & Double-Entry Accounting
        register(new ScreenGuidance(
            "SETTINGS_MODULES_GL",
            "General Ledger & Double-Entry Accounting",
            "calculator",
            "Enterprise double-entry financial core providing automated sales/purchase journal vouchers, real-time trial balance balancing, and drill-down financial statements.",
            List.of(
                "1. Auto-post Journal Vouchers: Authorizing Tax Invoices or Purchase Bills generates Dr/Cr Journal Vouchers automatically in real time.",
                "2. Live Trial Balance: Continuous double-entry reconciliation across all 150+ ledgers ensures 0.00 balancing discrepancy.",
                "3. Financial Reporting: Direct drill-down from Trial Balance to Profit & Loss, Balance Sheet, and ledger statements."
            ),
            List.of(
                "✓ Real-World Example: Tax Invoice #INV-2026-0042 for ₹1,18,000 (Taxable ₹1,00,000 + ₹9,000 CGST + ₹9,000 SGST) to M/s Shailesh Steel automatically posts: Dr. Shailesh Steel A/c ₹1,18,000 | Cr. Sales Revenue ₹1,00,000 | Cr. Output CGST ₹9,000 | Cr. Output SGST ₹9,000.",
                "✓ When Enabled (ON): Eliminates manual journal entry; sales, purchases, bank receipts, and payments update ledgers immediately.",
                "✓ Multi-Currency Ready: Supports canonical foreign exchange conversion with auto-calculated realized gain/loss."
            ),
            List.of(
                "⚠️ When Disabled (OFF): Double-entry journal voucher generation is paused. Invoices remain in subledgers without feeding General Ledger.",
                "⚠️ Settlement Safeguard: Ensure prior accounting periods are closed and audited before modifying core accounting parameters."
            ),
            List.of("Save Modules : Apply Changes")
        ));

        // 10b. GST Statutory Compliance & GSTR-2B
        register(new ScreenGuidance(
            "SETTINGS_MODULES_GST",
            "GST Statutory Compliance & GSTR-2B Auto-Reconciliation",
            "file-contract",
            "Statutory taxation engine enforcing Place of Supply tax determination, government GSTR-1 JSON exports, GSTR-2B ITC fuzzy reconciliation, and Sec 194Q/206C TDS/TCS withholding.",
            List.of(
                "1. Strict Place of Supply Gating: Validates customer/vendor GSTIN state codes against billing and delivery addresses before invoice generation.",
                "2. GSTR-2B Auto-Reconciliation: Ingests government portal JSON files and fuzzy-matches supplier invoices by GSTIN, bill number, date, and tax amounts within ₹1 tolerance.",
                "3. TDS / TCS Withholding: Tracks cumulative purchase/sales turnover thresholds to auto-deduct Section 194Q TDS or Section 206C TCS."
            ),
            List.of(
                "✓ Place of Supply Example: Billing to customer with Gujarat GSTIN (24ABCDE1234F1Z5) automatically enforces CGST (9%) + SGST (9%). Attempting to apply IGST is strictly blocked under GST Section 7/8.",
                "✓ GSTR-2B Matching Example: Supplier bill of ₹59,000 is matched against portal JSON. Reconciled bills are tagged 'ITC Eligible'; unfiled bills are flagged 'Vendor Default / ITC at Risk'.",
                "✓ TDS 194Q Example: When cumulative purchases from a supplier exceed ₹50 Lakhs in the financial year, subsequent bills auto-deduct 0.1% TDS (e.g. ₹10 Lakhs billing deducts ₹1,000 TDS for Form 26Q filing).",
                "✓ When Enabled (ON): 100% statutory compliance, zero tax determination errors, and automated Input Tax Credit (ITC) claim protection."
            ),
            List.of(
                "⚠️ When Disabled (OFF): Place of supply gating is relaxed, risking incorrect IGST/CGST filing notices from government tax authorities.",
                "⚠️ Government Portal Sync: Always verify government portal JSON format version before importing large GSTR-2B quarterly dumps."
            ),
            List.of("Save Modules : Apply Changes")
        ));

        // 10c. Smart Operational Automations Suite
        register(new ScreenGuidance(
            "SETTINGS_MODULES_AUTOMATIONS",
            "Smart Operational Automations Suite",
            "cog",
            "Autonomous operational guardrails automating purchase bill verification, debit note generation, dynamic stock replenishment, and customer overdue credit hard stops.",
            List.of(
                "1. Smart 3-Way Matching: Cross-checks Purchase Order (PO), Goods Receipt Note (GRN), and vendor Purchase Bill quantities and unit rates.",
                "2. Auto-generate draft debit notes: Generates draft debit notes automatically whenever vendor invoice exceeds accepted GRN received quantities.",
                "3. Dynamic Safety Stock: Continuously monitors supplier lead times and daily average consumption to raise reorder alerts.",
                "4. Customer Credit Hard Stop: Blocks new sales order and invoice creation (F9) for customers with overdue unpaid balances or credit limit breaches."
            ),
            List.of(
                "✓ 3-Way Match Example: PO ordered 100 kg at ₹400/kg. GRN received 90 kg. Vendor bills 100 kg at ₹420/kg. System flags 10 kg shortfall and +₹20 rate hike, blocking unauthorized payment.",
                "✓ Debit Note Example: PO ordered 50 bearings; GRN received 45 (5 damaged). Approving bill auto-creates ₹2,500 draft Debit Note against vendor ledger for Short Shipment.",
                "✓ Credit Hard Stop Example: Customer M/s Apex Fabricators has a 30-day payment term and an unpaid bill of ₹1,15,000 pending for 45 days. Pressing F9 (New Sale) hard-blocks invoice creation until management override or collection.",
                "✓ Safety Stock Example: Item SKF 6204 has 7-day lead time and 4 units/day usage. Stock hitting 28 units instantly triggers an automated dashboard reorder alert.",
                "✓ When Enabled (ON): Guarantees zero revenue leakage, prevents short shipment overpayments, and halts bad debt accumulation."
            ),
            List.of(
                "⚠️ When Disabled (OFF): All four automations operate in passive manual mode, relying entirely on operator manual inspection.",
                "⚠️ Override Authority: Customer credit hard stop can only be overridden by users with 'SALES.CREDIT_OVERRIDE' security permission."
            ),
            List.of("Save Modules : Apply Changes")
        ));

        // 10d. Executive 8:00 PM Daily Briefing
        register(new ScreenGuidance(
            "SETTINGS_MODULES_BRIEFING",
            "Executive 8:00 PM Daily Briefing",
            "bell",
            "Autonomous evening financial digest delivered directly to leadership WhatsApp numbers and email inboxes at 8:00 PM IST daily.",
            List.of(
                "1. Autonomous Compilation: At 8:00 PM IST every evening, the server compiles today's gross sales, collections received, vendor payments, liquid bank balances, and top overdue accounts.",
                "2. Multi-Channel Dispatch: Delivers formatted WhatsApp message via WAHA and attached financial digest PDF via corporate SMTP.",
                "3. Delivery Audit: Every dispatch logs to Communication Center with recipient, timestamp, and SENT/FAILED status."
            ),
            List.of(
                "✓ Real-World Example: Leadership receives instant WhatsApp summary at 8:00 PM: 'Today's Sales: ₹4,82,500 (6 Invoices) | Collections: ₹3,15,000 | Vendor Payouts: ₹1,20,000 | Bank Balance: ₹28,45,200 | Top Overdue: M/s Radhe (₹92k, 42d)'.",
                "✓ Test Dispatch: Use 'Send Test Briefing Now' button to preview the multi-user broadcast instantly.",
                "✓ Multi-Recipient Support: Configure multiple comma-separated executive phone numbers and emails."
            ),
            List.of(
                "⚠️ WhatsApp Pairing Requirement: Ensure WAHA session is connected in Settings → WhatsApp for WhatsApp dispatches to deliver.",
                "⚠️ SMTP Gateway: Verify corporate email credentials under Settings → Email Configuration for PDF delivery."
            ),
            List.of("Save Modules : Apply Changes")
        ));

        // 10e. Warehouse, Batch/FEFO & Serial Registry
        register(new ScreenGuidance(
            "SETTINGS_MODULES_BATCH_SERIAL",
            "Multi-Warehouse, Batch/Expiry (FEFO) & Serial Registry",
            "warehouse",
            "Depot-level inventory partitioning, First-Expired First-Out (FEFO) picking enforcement, and equipment serial number warranty management.",
            List.of(
                "1. Depot Partitioning: Separates physical stock across multiple factory warehouses, storage sheds, and transit depots.",
                "2. FEFO Dispatch: Enforces First-Expired First-Out picking for perishable and chemical goods, preventing stock expiration.",
                "3. Serial Registry: Assigns unique serial numbers to manufactured machines, linking units to warranty terms and service logs."
            ),
            List.of(
                "✓ FEFO Example: Warehouse has Batch A (expiring 15-Dec) and Batch B (expiring 30-Jan). Dispatch packing strictly requires Batch A to be scanned first.",
                "✓ Serial Example: High-pressure pump JASVI-PUMP-2026-0891 links invoice date to 12-month customer warranty and service history.",
                "✓ When Enabled (ON): Warehouse registers display depot dropdowns, batch expiry tables, and serial number validation pickers."
            ),
            List.of(
                "⚠️ Mandatory Tracking: When enabled, sales invoices cannot be saved without selecting valid batch numbers or serials for tracked items.",
                "⚠️ Historical Inventory: Existing unbatched stock should be assigned default batch numbers before enabling strict enforcement."
            ),
            List.of("Save Modules : Apply Changes")
        ));

        // 10f. Smart Overdue Collections & Statement Dispatch
        register(new ScreenGuidance(
            "SETTINGS_MODULES_COLLECTIONS",
            "Smart Overdue Collections & Aging Statement Dispatch",
            "envelope-open-text",
            "Aging Analysis 1-click WhatsApp and Email payment reminders with auto-generated Branded Party Aging Statement PDFs and cooldown spam protection.",
            List.of(
                "1. Aging Review: In AR Aging Analysis, view overdue buckets (30, 60, 90+ days) and click 1-click WhatsApp/Email dispatch.",
                "2. PDF Generation: Auto-generates branded Statement of Account PDF with company header, outstanding invoice list, and UPI QR code.",
                "3. Cooldown Guard: Enforces configured cooldown hours (e.g. 24h) to prevent multiple staff members from spamming the same party."
            ),
            List.of(
                "✓ Real-World Example: Client M/s Royal Polymers is 65 days overdue. 1-click dispatch sends WhatsApp reminder with attached branded PDF statement and logs event in Communication Center with 'Last Reminded' badge update.",
                "✓ Dynamic Severity: Selects polite, firm, or critical reminder templates based on invoice delinquency tier (30d vs 60d vs 90d+).",
                "✓ When Enabled (ON): Aging analysis provides 1-click reminders, cooldown protection, and full delivery audit trail."
            ),
            List.of(
                "⚠️ When Disabled (OFF): External messaging is bypassed immediately; reminders fall back strictly to internal Reminder Center notes.",
                "⚠️ Communication Center: All reminder dispatches are permanently audited with delivery status and recipient phone/email."
            ),
            List.of("Save Modules : Apply Changes")
        ));

        // 10g. Bank Statement Smart Narration Auto-Rules Engine
        register(new ScreenGuidance(
            "SETTINGS_MODULES_BANK_RULES",
            "Bank Statement Smart Narration Auto-Rules Engine",
            "university",
            "Rule-based narration pattern matching for recurring bank statement imports (rent, UPI, NEFT, charges) to accelerate bank reconciliation.",
            List.of(
                "1. Narration Learning: In Bank Statement, click 'Remember Rule' when reconciling recurring entries (e.g. 'UPI/RENT').",
                "2. Auto-Suggest: Future statement imports evaluate saved rules and pre-select the appropriate ledger with confidence scores.",
                "3. Auto-Apply: High-confidence routine entries (e.g. Bank Charges) can be configured to auto-reconcile on import."
            ),
            List.of(
                "✓ Real-World Example: Rule matches 'UPI/RENT/COMMERCIAL'. Importing monthly HDFC bank statement auto-routes the ₹45,000 debit directly to Office Rent Expense A/c with 1-click confirmation.",
                "✓ When Enabled (ON): Accelerates bank reconciliation from hours to minutes with zero manual typing.",
                "✓ Audit Trace: Every rule application records rule ID, execution timestamp, and matching score in the statement transaction."
            ),
            List.of(
                "⚠️ When Disabled (OFF): Hides Rules UI in Bank Statement and skips all rule evaluation during CSV/PDF import (100% classic manual reconciliation).",
                "⚠️ Data Retention: Disabling rules engine leaves all existing rules and past reconciliations safely intact in PostgreSQL."
            ),
            List.of("Save Modules : Apply Changes")
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
                "How Matching Works: Match Transaction → The application searches automatically → Review the suggestion → Confirm or find another → Linked Bank / Expense Entry is stored in ledger.",
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

        // 12. Sales Return & Credit Notes
        register(new ScreenGuidance(
            "CREDIT_NOTE",
            "Sales Return & Credit Notes — Statutory & Operational Guide",
            "document",
            "Manage customer sales returns and financial credit notes with automated inventory restock, customer ledger balance credit, and statutory GST Output Tax reversal (CGST Section 34).",
            List.of(
                "Categorize return: Choose 'Physical Goods Return' if customer is returning defective/unwanted merchandise, or 'Price / Discount Adjustment' for commercial rebates and rate differences.",
                "Mandatory Invoice Linking: Select the original sales invoice number so the credit note links properly to the tax invoice in GSTR-1 Table 9B (CDNR).",
                "Automatic Stock Movement: Marking 'Physical Goods Return' automatically adjusts inventory and creates a stock ledger return entry.",
                "Ledger Posting: Customer ledger is immediately credited by the note amount, reducing Accounts Receivable.",
                "Statutory GST Reversal: System calculates IGST/CGST/SGST tax components from the original invoice and marks tax liability for reversal in GSTR-1 and GSTR-3B Table 3.1."
            ),
            List.of(
                "Physical returns automatically update item master stock and reflect in stock valuation reports.",
                "Financial price adjustments alter customer ledger and tax liabilities without touching warehouse inventory levels.",
                "One-click 'Apply to Invoice' allows offsetting outstanding unpaid balances directly from the register."
            ),
            List.of(
                "CRITICAL STATUTORY DEADLINE: Under Section 34(2) of CGST Act, credit notes for a financial year must be declared on or before 30th November following the end of that financial year, or date of filing annual return, whichever is earlier.",
                "Do not mark 'Physical Goods Return' until goods have been physically received and inspected at the warehouse.",
                "Once approved and issued, credit notes cannot be silently altered without an official cancellation audit trail."
            ),
            List.of("Ctrl + N : New Credit Note", "F5 : Refresh Register", "Ctrl + P : Print / Export Note")
        ));

        // 13. Purchase Return & Debit Notes
        register(new ScreenGuidance(
            "DEBIT_NOTE",
            "Purchase Return & Debit Notes — Statutory & Operational Guide",
            "document",
            "Manage supplier purchase returns and debit notes with automated warehouse stock deduction, supplier ledger balance debit, and statutory Input Tax Credit (ITC) reversal (GSTR-3B Table 4B(2)).",
            List.of(
                "Categorize return: Choose 'Physical Goods Return' when returning damaged or rejected materials back to the vendor, or 'Rate / Shortage Adjustment' for supplier overcharges.",
                "Link Original Purchase Bill: Bind to the supplier's purchase invoice number and date for accurate statutory audit and GSTR-2B reconciliation.",
                "Stock Deduction: Physical returns deduct units from inventory holding, updating warehouse stock in real time.",
                "Supplier Ledger Debit: Reduces Accounts Payable balance owed to the vendor.",
                "Input Tax Credit (ITC) Reversal: In accordance with GST statutory provisions, previously claimed ITC is marked for mandatory reversal under GSTR-3B Table 4B(2)."
            ),
            List.of(
                "Attaching the vendor delivery challan and return gate pass guarantees audit readiness.",
                "Debit notes ensure you never overpay suppliers on rate discrepancies or damaged consignments."
            ),
            List.of(
                "CRITICAL STATUTORY REVERSAL: When goods are returned to a supplier, claiming Input Tax Credit on those goods is illegal under CGST rules; ITC must be reversed in the corresponding return filing.",
                "Ensure outward dispatch gate pass or courier tracking is confirmed before finalizing physical vendor return."
            ),
            List.of("Ctrl + N : New Debit Note", "F5 : Refresh Register", "Ctrl + P : Print / Export Note")
        ));
    }
}
