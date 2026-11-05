# Global User Environment & Workspace Conventions

## Jasvi ERP Unified Operational Lifecycle & Approval Workflow
All 4 ecosystem repositories (`Jasvi-Desktop`, `Jasvi-Admin`, `Jasvi-UserView`, `Jasvi-Mobile`) strictly follow this operational pattern:

1. **Tier 1: Antigravity Active Dev Workspace:** `D:\Antigravity-Dev\<Repo>`
   - The primary active coding workshop. All code modifications, refactoring, compiler checks (`mvn clean test`, etc.), and quality gate validations are performed here first.
   - Zero extraneous files are permitted here.

2. **Tier 2: Upfront Architectural Investigation & Root Cause Plan Phase**
   - Conducted strictly under Tier 1 workspace (`D:\Antigravity-Dev\<Repo>`).
   - On every user request, bug report, or feature build, conduct an exhaustive end-to-end investigation across the entire stack:
     - **FXML:** Layouts, hierarchies, tables, buttons, responsive sizing.
     - **Controller:** State, event handlers, background task executors, CSS style class binding.
     - **DTO & Shared Contracts:** Calculation engines, payload shapes, serialization, validation.
     - **API & REST Endpoints:** Spring Boot `@RestController`, routes, query parameters, status codes.
     - **Server Services & Repositories:** Business logic, transactions, entity relationships, query performance.
     - **Database & Migrations:** PostgreSQL schema, Flyway migrations, constraints, foreign keys.
   - **Mandatory Investigation Presentation Structure:**
     - **The Why (Root Cause):** Grounded in concrete code evidence.
     - **The How (Step-by-Step Plan):** Concrete modifications to apply with impacted files and line ranges.
     - **Canonical Rules Followed:** Explicit list of which canonical rules were evaluated and applied (e.g., Rule 4 Java 25, Rule 5 Theme Parity & Zero Color Leaks, Rule 6 Colorized KPIs, Rule 7 Dynamic Tables, Rule 8 Colorized Actions, Rule 9 Uniform Icons, Rule 11 Colorized Status, Rule 12 Responsive Bounds, Rule 13 Settings Dates, etc.).
     - **Linked Functionality Count & Inventory:** Exact count and list of all linked functionalities, screens, and endpoints connected to this code path.
     - **Ripple Impact Assessment:** Concrete breakdown of how many downstream functionalities will be influenced or touched.
     - **Explicit Zero-Regression Guarantee:** Verifiable safeguards proving existing functionality will not break.
   - **STRICT BINDING CONSTRAINT:** **NEVER build, modify, or update code until the user has explicitly confirmed and approved the plan.**

3. **Clubbed Tier 3 & Tier 4: Approval & Execution Modes**
   The execution and release phases are strictly separated by the user's explicit command:
   
   - **Mode A: Local Dev Approval (`"approved"` / `"apply fix"`):**
     - Triggered when the user confirms with **"approved"** (or requests local application).
     - Antigravity works strictly in Tier 1 (`D:\Antigravity-Dev\<Repo>`) to apply and resolve all issues, defects, and enhancements.
     - Runs local compiler checks, unit/integration tests, and automated release gates (`python scripts/audit-release-gates.py`).
     - **STRICT LOCAL ISOLATION:** All code changes remain strictly inside Antigravity's active dev workspace (`D:\Antigravity-Dev\<Repo>`).
     - **Does NOT push to GitHub.**
     - **Does NOT sync to other folders** (Tier 2 IntelliJ copy `D:\JavaProject` and Tier 3 Staging mirror `D:\GitHub\ReleaseSource` are not touched).
     - Reports local verification results to the user.

   - **Mode B: GitHub Release & Universal Sync (`"approved and push to github"` / `"sync and push to github"` / `"sync and release"`):**
     - Triggered when the user explicitly confirms **"approved and push to github"** or **"sync and push to github"**.
     - Antigravity applies all approved fixes in Tier 1 (`D:\Antigravity-Dev\<Repo>`) and validates local compiler and release gates.
     - **Synchronizes all copies to all folders:**
       - **Tier 2 (User's Protected IntelliJ Copy):** `D:\JavaProject\<Repo>` (IntelliJ `.idea` directory is strictly protected and never overwritten).
       - **Tier 3 (Verified Pre-Release Staging Mirror):** `D:\GitHub\ReleaseSource-<Repo>`.
       - **Tier 4 (GitHub Production Repo):** `D:\GitHub\<Repo>`.
     - In Tier 4 repository (`D:\GitHub\<Repo>`):
       - Creates a release branch (`release-vXX.X.XX`).
       - Commits and pushes to remote.
       - Opens a Pull Request (PR) to `main`.
       - Enables auto-merge (or merges once all PR CI checks turn GREEN).
       - Monitors `main` branch until CI workflow run is completely GREEN.
       - Creates and pushes release tag (`vXX.X.XX`) on the verified green `main` commit to trigger official release packaging.
       - Monitors and confirms release build and server deployment complete successfully.

4. **Post-Release Green Completion Audit (Mandatory Once GitHub is Green)**
   Once GitHub Actions CI, binaries, and deployments are completely **GREEN**, Antigravity MUST output a structured final audit containing:
   - **Original Scope Reconciliation:** Exact recap of what was requested and approved.
   - **The How (Implementation Details):** Concrete breakdown of how each item was built/fixed, including exact file paths, classes, and line references.
   - **Linked Functionalities Impacted:** Full inventory and exact count of all functionalities linked to and affected by this fix.
   - **Zero-Regression Guarantee & Proactive Defect Transparency:**
     - Verifiable confirmation that no existing workflows were broken.
     - **Mandatory Defect Transparency:** If ANY regression or unexpected failure was discovered during local testing or CI builds, Antigravity MUST explicitly declare:
       > *"During automated test execution / CI validation, regression [Issue X] was detected in [Component Y]. It was immediately diagnosed, corrected, and verified before release."*

5. **Protected Local Runtime Workspace:** `D:\DSE Production`
   - Live PostgreSQL database (`port 55432`), configs, runtime logs, attachments, backups.
   - **NEVER** run destructive SQL (`TRUNCATE`, `DROP`, `DELETE`) or alter database/runtime files here unless explicitly instructed.

---

## The 20 Canonical Operating, Engineering & UI/UX Rules (Strictly Binding)

### Rule 1: Zero Extraneous Files Across All Copies (No .txt, .log, arbitrary .md, defect dumps)
- **NEVER** create, add, or sync any extraneous file into ANY repository workspace or tier (`D:\Antigravity-Dev\`, `D:\JavaProject\`, `D:\GitHub\ReleaseSource-*`, `D:\GitHub\`).
- Forbidden files: No defect logs, no `.txt` scratch files, no arbitrary `.md` notes, no temporary checklists, no test dumps.
- ONLY files that are strictly an integral part of the application code itself are allowed.
- Whitelisted markdown files: `README.md`, `dse-erp-master-protocol.md`, `oracle.md`, `GEMINI.md`, `dynamic_showcase_architecture_spec.md`, `github-deployment-setup.md`, `rollback.md`, `release-notes.md`, and files under `docs/`.
- All temporary analysis, scratch notes, and investigation logs MUST be kept exclusively inside Antigravity's session artifact/scratch directory (`<appDataDir>\brain\...`), NEVER in the project workspaces.
- **Mandatory Universal Protocol Synchronization:** Whenever `dse-erp-master-protocol.md`, `oracle.md`, or `GEMINI.md` is updated, run `python scripts/sync-to-all.py` to synchronize across all 4 ecosystem repositories and tiers.

### Rule 2: Automatic Next-Release Version Targeting
- Once an application version is built, tagged, or live (e.g. `v11.0.44`), any subsequent user request, bug fix, or feature automatically targets the **NEXT incremental release version** (e.g. `v11.0.45`).
- Automatically manage version numbering in `.mvn/maven.config`, release notes in `release-notes.md`, and Flyway migration file prefixes (e.g. `V11_0_45__*.sql`).

### Rule 3: Smart AI Global Synthesis & Cumulative Multi-Turn Context (Zero Dropped Questions & Zero Hasty Answers)
- **Never answer hastily or superficially** based solely on the user's latest question in isolation.
- Act as an intelligent, holistic system architect: review all previous turns, questions, attachments, and open defects globally.
- When the user asks follow-up questions, adds constraints, or poses 10 to 20 inquiries across multiple turns, answering new questions must **NEVER** cause prior questions or findings to be lost.
- Responses must synthesize cumulative context, maintaining a unified summary of all previous inquiries and answers alongside the current request so that both parties remain 100% aligned.

### Rule 4: Modern Java 25 Standard
- Always leverage Java 25 latest syntax, idioms, pattern matching, records, virtual threads, modern switch expressions, sealed hierarchies, and stream enhancements.
- Code must be lean, deterministic, thread-safe, and free of obsolete legacy patterns.

### Rule 5: Universal Light & Dark Theme Parity & Zero Controller Color Leaks
- Every screen, component, modal dialog, and table must render flawlessly with full visual parity in both Light and Dark themes.
- **Zero Controller Color Leaks:** NEVER place hex colors (`#[0-9A-Fa-f]{6}`) or inline JavaFX color properties (`-fx-background-color`, `-fx-text-fill`, `-fx-icon-color`, `-fx-border-color`) inside Java controllers (`desktop/src/main/java/org/example/controller/`). Always use CSS classes (`getStyleClass().add(...)`).

### Rule 6: Colorized Dynamic KPI Summary Cards
- Every KPI card across all screens must have its **Label**, **Icon**, and **Value** styled in distinct semantic colors (using theme accent/success/warning/primary/info CSS classes).
- Plain, unstyled monochrome or muted grey text is strictly prohibited.

### Rule 7: Dynamic Responsive Table Widths & Headers
- Table columns must **NEVER** have hardcoded fixed widths (`prefWidth="..."`) in FXML or Java controllers.
- Dynamic width distribution / percentage allocation (`table.widthProperty().multiply(...)`) must be used (matching standard screen conventions) so columns scale cleanly with window resizing and avoid clipping or horizontal scrollbars.

### Rule 8: Colorized Action Lists & Context Menus
- All action lists, dropdown actions, table row context menus, and action drawers must have both **Icon** and **Label** rendered in semantic theme colors.

### Rule 9: Icon Uniformity & Reusability Across Identical Actions
- The same action (e.g. Edit, Delete, View, Print, Export, Refresh, Duplicate, Cancel) must reuse the exact same standardized icon across all screens and action menus.
- Never use disparate, conflicting, or non-standard icons for identical operations.

### Rule 10: Automation Gate Compliance & Continuous Self-Learning from CI
- Whenever buttons, FXML elements, or controllers change, all automation gates (`SharedUiOwnershipContractTest`, `audit-release-gates.py`, UI design system audits) must pass locally.
- **Pre-Validation Guarantee:** Adding, moving, or removing UI elements directly impacts automated CI validation; conduct rigorous local pre-validation before any release is triggered.
- **Mandatory Full Reactor Pre-Flight Test:** Never run tests on a single submodule alone (e.g. `-pl server`). Before opening any PR or pushing code to Tier 4, execute a full reactor test run (`.\mvnw.cmd test` or `./mvnw test` across all modules: parent, shared, server, desktop) and verify 0 failures and 0 errors.
- **Source-Scanning Contract Test Invariance:** Architecture contract tests (`SharedUiOwnershipContractTest`, `GlobalUiContractTest`, `UiArchitectureContractTest`) perform exact string pattern checks on Java source files. When modifying controllers or actions, maintain strict compatibility with the token patterns expected by the contract tests (e.g., whitespace and style class sequences).
- **Outbound HTTP / External Network Decoupling from Transactions:** Never invoke external third-party HTTP endpoints (such as WAHA WhatsApp or Mail server) inside a database `@Transactional` block. Auxiliary audit persistence must use `@Transactional(propagation = Propagation.REQUIRES_NEW)` so network failures or secondary log errors cannot roll back successful communication transmissions.
- **Continuous Self-Learning from CI:** If GitHub Actions CI ever rejects or fails a build:
  1. Immediately extract the failed step logs via `gh run view <run-id> --log-failed`.
  2. Pinpoint the root cause down to the exact test or assertion failure.
  3. Formulate the permanent prevention rule, update `dse-erp-master-protocol.md`, and synchronize to all repos and tiers via `python scripts/sync-to-all.py`.
  4. Adhere to the prevention rule permanently in all future turns.

### Rule 11: Colorful Label Icons, Semantic Theme-Colored Values & Status Badges
- Every form field label, property header, and metadata descriptor must feature an appropriate, colorful semantic icon (reusing standardized icons per Rule 9, without assigning conflicting icons like generic user icons to unrelated fields).
- Both the label name AND its displayed value must be styled in vibrant, distinct semantic theme colors (via CSS style classes) rather than plain unstyled text, ensuring high contrast, clarity, and visual hierarchy.
- All status badges, critical table cell data (e.g. amounts, balances, invoice status, dates, active/inactive), and key labels must use semantic colors defined by the theme CSS system for maximum contrast and visual hierarchy.

### Rule 12: Content-Adaptive Dialog & Modal Bounds (Zero Truncation Guarantee)
- All modal dialogs, alert boxes, action drawers, and inspect popups must provide generous internal spacing, margins, and padding.
- **Zero Truncation Guarantee:** Under NO circumstances may any container, button, label, table header, or table cell data be clipped, cut off, or ellipsized with dots (`...`).
- Implement dynamic, content-driven auto-sizing rules (`minWidth`, `minHeight`, responsive bounds calculation) so dialogs and popups adapt cleanly to their content across varying resolutions without causing rendering lag or performance bottlenecks. Popups and inspect modals must also support dragging and window repositioning/resizing.

### Rule 13: Settings-Driven Date Formatting
- Date display and parsing across all screens, tables, PDF generators, and forms must strictly follow application configuration / user profile date settings (`AppConfig`), never arbitrary or hardcoded date format strings.

### Rule 14: UserView Strict Deployment Protocol (Push = UAT Only, PROD is 100% Manual)
- `Jasvi-UserView` must **NEVER** deploy to the Production environment (`https://jasviindustries.in`) automatically.
- Any automated build triggered by a commit, merge, or push to `main` (or tags) deploys **strictly and exclusively to UAT** (`https://web-uat.jasviindustries.in`).
- Production deployment is **100% manual**, executed only when the user explicitly triggers GitHub Actions workflow dispatch (`target: prod-upcoming-only` or `target: prod-full-showcase`).
- By default, Production strictly remains locked to the Upcoming Page (`upcoming.html`).

### Rule 15: Mandatory Default Plugin Quality Gate (Always Run by Default)
- Before proposing any code change, plan, or build to the user, run and output the mandatory **Default Plugin Quality Gate**:
  1. **Web Standards & Layout Gate (`modern-web-guidance`):** Audits HTML5 semantics, flex/grid layouts, responsiveness, and dark/light consistency.
  2. **Security & Vulnerability Gate (`cloudflare-security-audit`):** Audits for XSS vectors, insecure DOM injections, token handling, and unsafe resource imports.
  3. **Lean Code & Anti-Bloat Gate (`ponytail`):** Audits for minimal surgical diffs, zero dead code, and zero dependency creep.
  4. **Application Vendor Plugin Health Gate (`jspdf`, `xlsx`, `swiper`):** Verifies availability guards (`window.jspdf`, `window.XLSX`, `window.Swiper`) and preserves pure deterministic calculations (`DocumentCalculationEngine.js`).
  5. **Desktop Theme Ownership & Release Contract Gate (`SharedUiOwnershipContractTest` & `audit-release-gates.py`):** Enforces zero controller color leaks and 100% gate compliance before syncing or releasing.

### Rule 16: Mandatory Scope Reconciliation & Build Completion Audit (100% Delivery Guarantee)
- **Zero Dropped or Deferred Scope Items:** Every single deliverable agreed upon in an approved scope plan, scope artifact (`scope*.md`), or user request prompt is strictly binding. No build, PR, or release may be declared complete until 100% of all scoped items are fully implemented, verified, and reconciled against the codebase.
- **Mandatory End-of-Build Audit Summary:** At the completion of every build or release turn, Antigravity MUST output a structured **Scope Reconciliation Audit Table** containing:
  1. **Feature / Component:** Every scoped feature, screen, endpoint, and bug fix.
  2. **Scoped Requirement:** Concrete specification promised in the scope.
  3. **Built Deliverable:** Exact file paths, methods, and line ranges where the code was built.
  4. **Rule & Quality Gate Compliance:** Verification against canonical rules (Rule 5 Theme Parity & Zero Color Leaks, Rule 6 Colorized KPI Cards, Rule 7 Dynamic Tables, Rule 8 Colorized Actions, Rule 9 Uniform Icons, Rule 11 Colorized Status Badges, Rule 12 Responsive Modal Bounds).
  5. **Status:** Explicit verification (`[100% COMPLETE - VERIFIED]`).
- **Zero Silent Deferrals:** If an item cannot be completed due to missing technical dependencies, it must NEVER be silently omitted. It must be explicitly highlighted to the user with actionable next steps, keeping the scope transparent and unbroken.
- **Mandatory Proactive Defect Disclosure:** If ANY regression was identified and resolved during the build, test, or CI cycle, it must be explicitly reported to the user in the completion audit.

### Rule 17: Zero Mock or Dummy Data in Builds (Authentic Integration Guarantee)
- **Never** use mock, simulated, placeholder, or hardcoded dummy data as part of any desktop build, screen, or release.
- All application features—including Live GST lookups, Bill-to-Tally automation, OCR extraction, inventory tables, and document templates—must integrate with real database persistence, authentic live REST endpoints, and valid Spring Boot services.
- If an external integration or live API is temporarily unreachable or unauthenticated, surface real error feedback and live connectivity state to the user (e.g. RFC 7807 ProblemDetail) rather than silently substituting fake or simulated data.

### Rule 18: Exhaustive Pre-Change Dependency & Linked Functionality Blast Radius Audit
- Before changing any code, method, DTO, shared utility, or CSS class, first check how many functionalities across the application are linked to that code.
- If a proposed change is linked to 50, 60, or more places across the codebase, exhaustively investigate all linked call sites and confirm that modifying the code will not break, regress, or alter behavior in any connected functionality.
- Explicitly document the full linked functionality inventory and ripple impact assessment in the Tier 2 investigation report before requesting approval.

### Rule 19: Zero Assumptions & Interactive User Clarification on Prompt
- **Never assume anything.** If requirements, edge cases, UX workflows, or technical options are ambiguous or unclear, do not guess or unilaterally proceed.
- Ask questions directly to the user on the prompt, presenting clear ideas and structured options for the user to review and select.
- **Strict Binding Constraint:** NEVER build, alter, or commit any code without the user's explicit prior confirmation and approval of the plan.

### Rule 20: Architectural Cohesion & Non-Duplication (Zero Competing Logic)
- When introducing or updating code, ensure the new implementation does not duplicate existing classes, services, or helper functions.
- Ensure new code and existing code do not fight each other or cause race conditions, double background triggers, or conflicting UI notifications (e.g. eliminating race conditions that produce concurrent "Clients Loaded" and "Load Failed: This action is already in progress" toasts).
- Maintain single, deterministic sources of truth across controllers and background task executors.
