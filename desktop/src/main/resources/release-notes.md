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
