# What's New in DSE ERP v11.0.42

### Comprehensive GST Portal Live Taxpayer Intelligence, Full Filing History & Dynamic Template Columns
- **Live GST Taxpayer Profile Intelligence**: Upgraded `verifyGstin()` to extract complete, genuine taxpayer compliance details: Legal Name of Business, Trade Name, Live GSTIN Status (`ACTIVE`, `CANCELLED`, `SUSPENDED`), Date of Registration (Effective Date), Taxpayer Type (`Regular`, `Composition`, `SEZ Unit`), Constitution of Business (`Proprietorship`, `Partnership`, `Private Limited Company`, `HUF`), State & Center Jurisdictions (Ward / Circle / Range), Principal Place of Business (Full Address), and Nature of Core Business Activities (`Manufacturer`, `Trader`, `Service Provider`).
- **Comprehensive "View Full GST Details" Dialog**: Added a dedicated dialog accessible from both Clients Directory and Invoice Template Builder displaying all 9 core taxpayer facts along with a real-time **Return Filing Status Table** showing filing dates, tax periods, and statuses (`FILED`, `PENDING`) for GSTR-1 and GSTR-3B across recent financial years.
- **Dynamic Table Column Architecture in Bill to Tally**: In `BillToTallyController`, selecting a client dynamically reconstructs both `tblBills` (top document table) and `tblLineItems` (bottom inventory table) matching the client's locked template version headers and item blueprints, providing true template-driven extraction.
- **Enhanced Sample File Header Extraction**: In `ClientTemplateController`, uploading sample bills (`.png`, `.jpg`, `.pdf`, `.xlsx`, `.csv`) now dynamically extracts both High-Level Document Headers (`Invoice No`, `Date`, `Vendor Name`, `GSTIN`, `Taxable Amount`, `Taxes`, `Total`) and Item-Level Column Blueprints.
- **Standardized Purple Action Menus (Rule 8)**: Standardized table actions in `ClientsDirectoryController` into the canonical Jasvi ERP purple `MenuButton` (`table-action-menu`) with styled items (`Manage Templates`, `View Full GST Details`, `Edit Client`, `Delete Client`) and zero truncated buttons.
- **Universal Dialog Window Controls & Freedom of Movement**: Upgraded `TallyDocumentInspectionDialog` and GST Dialogs with header mouse-drag movement across the screen, double-click to maximize/restore, and native minimize capabilities.
- **Colorized Semantic Labels with Distinct Icons (Rule 5 & Rule 9)**: Refactored client profile cards to eliminate generic user icons and assign distinct semantic theme colors and dedicated icons (`building` for company, `id-card` for GSTIN, `location` for address, `shield` for status, `calendar` for registration date, `tax` for taxpayer type).
- **Concurrent Task Loading Safety**: Eliminated spurious `"This action is already in progress"` error toasts by installing atomic loading guards across `ClientsDirectoryController` and `ClientTemplateController`.

# What's New in DSE ERP v11.0.41

### Intelligent Document Processing (IDP) Suite: Live GSTIN Registry, 3-Panel Schema Definition & Strict Quality Gate
- **Live GSTIN Master Driver & Dedicated Clients Screen**: Established GSTIN as the authoritative root identity. Added a dedicated `Clients` directory screen displaying live verified client records, legal firm names, trade names, registered addresses, and real-time GST status (`ACTIVE`, `CANCELLED`). Integrated `+ Add Client via GSTIN` with instant auto-fill and validation from the GST portal.
- **3-Panel Templates Schema Definition & Version Locking**: Introduced dedicated `Templates` screen to define document schemas without touching transactional data. Panel 1 shows the verified client profile; Panel 2 configures document headers (`Invoice No`, `Date`, `CGST`, `SGST`, `IGST`, `Total`); Panel 3 provides an interactive item-level column blueprint (`No`, `Item Name`, `HSN/SAC`, `Qty`, `Price`, `GST%`, `Discount`, `Net Amt`) with manual column additions, sample file testing, and version locking (`v1`, `v2`).
- **Sidebar Menu Reorganization**: Relocated `Bill to Tally` out of `Bank & Expense` into its own top-level menu group placed directly below `Bank & Expense`, providing three clean submenus: `Bill to Tally`, `Clients`, and `Templates`.
- **Dynamic Template-Driven Extraction (Zero Dummy Data)**: In `Bill to Tally`, uploaded invoices automatically detect the vendor's GSTIN, match the locked template version, and dynamically construct `TableView` columns according to the template blueprint, extracting 100% real line items and document totals.
- **Strict Mathematical Quality Gate & Export Blocker**: Enforces automated two-level reconciliation ($\sum \text{items} = \text{taxable}$ and $\text{taxable} + \text{taxes} = \text{total}$). If mathematical discrepancies exist, vouchers are flagged as `NEEDS_REVIEW` and exports (`Export GST Portal JSON`, `Push to TallyPrime`) are strictly blocked with explicit diagnostic mismatch alerts.
- **Universal Multi-Format Document Inspection Dialog**: Enhanced `TallyDocumentInspectionDialog` to preview all formats (`.pdf`, `.png`, `.jpg`, `.jpeg`, `.xlsx`, `.xls`, `.csv`) with embedded Apache POI spreadsheet grid, zoom/rotation controls, and native OS window minimize, maximize, and restore capabilities, accessible anytime from both screens.

# What's New in DSE ERP v11.0.40

### Bill-to-Tally Native Windows OCR Extraction, Scanned PDF Fallback, Multi-Format Export Previews & Automatic Downloads
- **Native Windows WinRT OCR Extraction**: Integrated Windows native OCR (`Windows.Media.Ocr.OcrEngine`) via `WindowsOcrEngine`, enabling automated text and data extraction from image invoices (`.png`, `.jpg`, `.jpeg`). Eliminates manual summary fallbacks and successfully parses real invoices, GSTINs, vendor names, dates, financial amounts, and line items.
- **Scanned Image-Only PDF Fallback**: Upgraded `TallyDocumentParser` to detect scanned/image-only PDFs where digital text extraction yields insufficient text. Automatically renders the document pages to high-resolution bitmaps and routes them through the native OCR engine.
- **Robust Multi-Line & Columnar Item Extraction**: Enhanced regex parsers to recognize multiline label/value sequences and tabular columnar structures, accurately extracting item names, HSN codes, quantities, rates, and tax amounts.
- **Interactive Export & Push Preview Dialogs**: Added `TallyExportPreviewDialog` for all 4 export and push actions (`Export Tally JSON`, `Export GST Portal JSON`, `Export Tally Excel`, and `Push to TallyPrime`). Users can review formatted JSON, GST Portal structures, Excel row summaries, and Tally XML request payloads before transmission.
- **Frictionless Direct Downloads (Zero FileChooser Prompts)**: Replaced painful OS `FileChooser` location prompts with direct automatic downloads to the user's standard `Downloads` folder (`~/Downloads/`), auto-generating clean timestamped files and triggering non-intrusive toast notifications.

# What's New in DSE ERP v11.0.39

### WhatsApp Transaction Isolation, Communication Center Dual-Channel Re-send, Bill-to-Tally Automation Streamlining & iText TableWidths Silencing
- **WhatsApp Transaction Isolation & Rollback Prevention**: Decoupled outbound HTTP network transmission in `BusinessWhatsappService` from active database transactions, eliminating `UnexpectedRollbackException` and ensuring messages sent via WAHA are not falsely reported as failed. Isolated audit log persistence into independent `REQUIRES_NEW` transactions with safe entity resolution.
- **Communication Center Dual-Channel Re-Send with Live Previews**: Added interactive `Re-send` buttons for both Email and WhatsApp in Communication Center. Resending now launches `WhatsappTransmissionDialog` and `EmailTransmissionDialog` with draft previews and monitors execution via `LiveOperationDialog` with multi-stage progress reporting.
- **Bill-to-Tally UI Streamlining & Purged Mock Data**: Purged all placeholder/mock data (`Logitech`, `Keychron`, `INV-9584`, `10000.00`) from `TallyDocumentParser`. Streamlined screen into two full-width tables (`Extracted Bills & Vouchers` and `Line Items`) without side panes or KPI cards. Added dedicated `GSTIN`, `GST`, `IGST`, and `Actions` columns, and integrated the full-size `TallyDocumentInspectionDialog` modal popup with high-resolution zooming, rotation, and paging.
- **iText TableWidths Warning Elimination**: Implemented strict percent array normalization in `ScheduledReportExportService`, `UnifiedReportExportService`, `ProfessionalDocumentRenderer`, and `TaxInvoicePdfGenerator`, ensuring all table column widths sum strictly <= 100.0% and eliminating server warnings.
- **CI Test Runner Memory Hardening**: Configured `maven-surefire-plugin` in root `pom.xml` with explicit JVM heap bounds (`-Xmx1024m -XX:+UseG1GC`) and single-fork execution to guarantee stable, deterministic CI execution on Windows runners.

# What's New in DSE ERP v11.0.38

### WhatsApp Multi-Channel Live Auto-Fallback, Hibernate Session Protection & Protocol Governance
- **WhatsApp Live Health-Aware Auto-Fallback**: Upgraded `BusinessWhatsappService` to inspect live WAHA session statuses across all channels. If a preferred or active sender channel is un-paired or stuck in initializing status (`STARTING`, `STOPPED`, `FAILED`), outgoing tax invoices and document dispatches automatically fall back to an active, authenticated `WORKING` channel (e.g. `session_9099939193`), preventing document delivery disruption.
- **Hibernate AssertionFailure (`HHH000099`) Prevention**: Resolved unique key constraint collision on `whatsapp_sender_channel_channel_code_key` by querying channels by unique channel code rather than filtering only active rows. Inactive channels are safely reactivated and updated, eliminating duplicate insert attempts and protecting Hibernate session integrity.
- **Full Transactional Dispatch Safety**: Hardened `sendDocument()` and `sendText()` boundaries with `@Transactional`, guaranteeing that entity resolution, communication audit logs, and `sales_header.whatsapp_sent` flags commit atomically without `TransactionRequiredException`.
- **Actionable WAHA QR Error Messaging**: Replaced generic and misleading "wait 5 seconds" alerts with actionable pairing guidance directing operators to authenticate in Settings > WhatsApp when no active sessions are paired.
- **Jasvi Ecosystem Master Protocol & Oracle Cloud Decoupling**: Unified the Jasvi ERP 4-Tier Operational Lifecycle across all repositories, codified the 15 Canonical Engineering & UI/UX Rules, decoupled Oracle OCI and Caddy specifications into `oracle.md`, and deployed the permanent universal synchronizer `scripts/sync-to-all.py`.

# What's New in DSE ERP v11.0.37

### Multi-Format Bill-to-Tally Automation Rebuild & WhatsApp Dispatch Resilience
- **Multi-Format Bill-to-Tally Automation Rebuild**: Rebuilt the Bill-to-Tally interface with standard enterprise KPI metric cards (Total Bills, Ready to Export, Needs Review, Exported, and Batch Gross Value). Added interactive inline editing for invoice numbers, Tally party ledgers, taxable amounts, and mapped stock items with real-time accuracy scoring and persistence.
- **WAHA Fast Non-Blocking Polling & Startup Resilience**: Optimized QR code retrieval and connection checks to return immediately during session initialization (`STARTING`), preventing reverse-proxy and desktop client timeouts (`request timed out`). Added automated retry logic when dispatching documents during headless browser warmup.
- **WhatsApp Communication Audit Transaction Safety**: Wrapped post-transmission audit logging and invoice status tracking in an active transaction, resolving `TransactionRequiredException` and ensuring `communication_log` records and `sales_header.whatsapp_sent` status are reliably committed.
- **iText PDF Column Width Normalization**: Normalized dynamic transport facts widths to sum to exactly 100%, silencing `TableWidths: Sum of table columns is greater than 100%` server warnings while preserving exact visual PDF formatting.

# What's New in DSE ERP v11.0.24

### Unified Bank Statement Engine, Payment Reference (`PRF/...`) Deep-Linking & Standardized Master Reference Formats
- **Unified Bank Statement Import Engine (`UniversalBankStatementParser`)**: Consolidated all bank statement ingestion and preflight validation under a single universal parser, added full Light Mode parity, normalized valid status badges to `PASSED`, and enabled multi-format template downloads (`.xlsx`, `.xls`, `.csv`, and All 3 Formats) with 5-color semantic headers.
- **Auto-Generated Statement Reference (`BST/...`) & Payment Reference (`PRF/...`)**: Added `Statement Ref` (`BST/DD-MM-YYYY/XXXX`) column to Bank Statement and Purchase Recon `# Linked Ref`, removed duplicate `Value Date`, and implemented auto-generated `Payment Ref` (`PRF/DD-MM-YYYY/XXXX`) across manual and bank-matched settlements with 2-way deep-link traversal and manual settlement warnings.
- **Standardized `PREFIX/DD-MM-YYYY/XXXX` Across All Master & Note Categories**: Standardized Business Type (`BUS/...`), Industry (`IND/...`), Currency (`CCY/...`), Time Zone (`TIM/...`), Date Format (`DAT/...`), Time Format (`TMF/...`), Reference Scheme (`RFS/...`), Credit/Debit Note Reasons (`CNR/...`, `DNR/...`), and Credit/Debit Notes (`CN/...`, `DN/...`) under the master `REFERENCE_FORMAT` engine.
- **2-Way Document Inspection Linkages & Deep-Link Isolation**: Added **"Linked Records"** tab and **"View Linkages"** actions to Sales and Purchase inspection modals and registers, fixed Expense Entry deep-link isolation, and enhanced Move to Expense / Bank Entry dialog ergonomics.

# What's New in DSE ERP v11.0.16

### Google Antigravity SDK Ecosystem, Glassmorphism Defect Highlighting & AI Copilot Diagnostics
- **Google Antigravity Java SDK Integration**: Integrated official `io.github.glaforge.antigravity:antigravity-sdk-wrapper:0.2.17` directly into the JavaFX 25 desktop application runtime, enabling autonomous agent and copilot capabilities.
- **Asynchronous, Non-Blocking Architecture (`ErpAgentService`)**: Built a thread-safe singleton managing the agent harness and system prompts, executing all agent interactions asynchronously via `CompletableFuture` with safety timeouts to keep the JavaFX UI 100% smooth at 60fps.
- **Dynamic Glassmorphic Defect Highlighting**: Implemented dynamic `installGlassDefectRowFactory` for JavaFX `TableView` components, applying translucent glass-red (`rgba(239, 68, 68, 0.16)`) for critical defects and glass-amber (`rgba(245, 158, 11, 0.14)`) for warnings with custom accent borders without interfering with row selection or hover transitions.
- **AI Defect Analysis in System Diagnostics**: Embedded an **"AI Defect Analysis"** action button and glassmorphic response card inside `SystemDiagnosticsDialog.java`, providing instant automated root cause analysis and actionable remediation advice for runtime exceptions and log events.
- **Deterministic Heuristic Fallback**: Implemented an automated offline analysis fallback ensuring enterprise-grade stability and zero crashes when running in air-gapped workstations or when external API keys are not configured.

# What's New in DSE ERP v11.0.15

### Top Bar Ergonomics, Light Mode Glassmorphism & Cloud Server Diagnostics
- **Top Bar Ergonomics & Decluttering**: Permanently removed WhatsApp and keyboard shortcut action buttons from the main shell top bar; System Diagnostics badge dynamically hides when no active alerts/errors are present.
- **Light Mode Glassmorphism Parity**: Register inspection drawers and modal windows (`RegisterDetailDrawer`) dynamically adapt to Light Mode using pearl acrylic glassmorphism styling, `#0f172a` deep slate typography, and high-contrast jewel tones (`#047857` Emerald, `#be123c` Crimson, `#b45309` Amber, `#0369a1` Slate/Teal).
- **Linux Cloud Server Diagnostics**: `AdminService` upgraded with robust cross-platform log discovery supporting Ubuntu/OCI Linux hosts (`/srv/dse-erp/uat/...`, `/var/log/...`, and systemd `journalctl -u dse-erp-uat.service`).
- **Real-Time Global Audit Trail**: Instant incremental filtering on search bar text changes and user dropdown selection without requiring manual Enter/Apply triggers.
- **Design System Ownership Parity**: Restored CSS class ownership in settings sub-cards and status badges to satisfy `SharedUiOwnershipContractTest`.

# What's New in DSE ERP v11.0.14

### Headless Public Showcase & Dual-Client 3D Showcase Studio
- **Dynamic 3D Machinery Showcase Engine**: Empowered administrators to manage public 3D coverflow workstations, machinery photography, precision tolerance specs, hero metrics, marquee ticker, and contact information dynamically from both the Web Admin Studio and Desktop ERP Client without code redeployment.
- **High-Performance Headless REST APIs**: Implemented sub-10ms in-memory cached endpoints (`GET /api/public/showcase`, `GET /api/public/showcase/media/{key}`, and `POST /api/public/showcase/rfq`) with SHA-256 ETag binary caching, backed safely by existing PostgreSQL `application_setting` and `server_resource` tables without altering database schema.
- **Desktop Company Settings Showcase Dock**: Integrated 1-click administrative launch dock directly into `CompanySettingsPanel.fxml`, keeping exact FXML count at 72 files and preserving full release audit compliance.

# What's New in DSE ERP v11.0.13

### Centralized Core Architecture & Master Protocol Section 3.3
- **Centralized System Standards**: Formally documented the 6 centralized core systems in `dse-erp-master-protocol.md` (High-Speed Document Inspection Modals, Unified SVG UI Design System, Concurrency & Freeze Prevention Thread Pools, Multi-Tab Workspace Navigation, RBAC Security & API Runtime, and Universal Document Studio Exporters).
- **Strict Clean Root Mandate**: Permanently purged historical `CHANGELOG-*.md` files and obsolete legacy protocols from repository roots, standardizing all release history exclusively in `release-notes.md`.
- **Ponytail Minimalist Architecture**: Integrated Ponytail (YAGNI / minimal diff ladder) to eliminate over-engineering, protect JavaFX memory heap, and ensure maximum desktop responsiveness.
