# Jasvi Ecosystem Master Protocol & Unified Environment Constitution

> **Single Source of Truth** for the unified Jasvi ERP ecosystem (`Jasvi-Desktop`, `Jasvi-Admin`, `Jasvi-UserView`, `Jasvi-Mobile`).  
> **Antigravity Enforcement:** This document is automatically loaded by Antigravity on every conversation. All rules, boundaries, and checklists herein are strictly binding.  
> **Last Updated:** 2026-11-04 — Full 4-Tier Operational Lifecycle Architecture, 15 Canonical Engineering & UI/UX Rules, OCI Infrastructure Decoupling (`oracle.md`), and Permanent Universal Synchronizer (`scripts/sync-to-all.py`).

---

## ⚡ Quick-Reference Executive Summary

### 1. The 4 Applications at a Glance
All 4 client applications communicate with **one shared Spring Boot backend** and **one PostgreSQL 16 database**:

```
                       [ Spring Boot Backend + PostgreSQL DB ]
                                          │
       ┌──────────────────┬───────────────┴───────────────┬──────────────────┐
       ▼                  ▼                               ▼                  ▼
1. Desktop App     2. Web Admin Portal             3. UserView Showcase   4. Mobile App
   (JavaFX 25)        (HTML5/CSS3/JS)                 (Dynamic 3D Portal)    (KMP / PWA)
   Flagship office    Full cloud browser ERP          Public machinery       Mobile app for
   workstation app.   for accounting/warehouse.       catalog & RFQ quotes.  sales & warehouse.
```

| Application | Tech Stack | Role & Target Audience | Live Cloud URL | Repository |
| :--- | :--- | :--- | :--- | :--- |
| **`Jasvi-Desktop`** | JavaFX 25 + Spring Boot | Main enterprise workstation system (74 FXML pages) | Local Workstation | `ssdhameliya/Jasvi-Desktop` |
| **`Jasvi-Admin`** | Pure HTML5 / CSS3 / ES6 | Web ERP admin client for remote staff | `https://erp.jasviindustries.in` | `ssdhameliya/Jasvi-Admin` |
| **`Jasvi-UserView`**| HTML5 / Swiper / 3D | Public showcase, machine specifications, RFQs | `https://jasviindustries.in` | `ssdhameliya/Jasvi-UserView` |
| **`Jasvi-Mobile`** | Kotlin Multiplatform / PWA | Cross-platform mobile gateway | `https://mobile-uat.jasviindustries.in` | `ssdhameliya/Jasvi-Mobile` |

---

## 1. The Unified 5-Tier Operational Lifecycle (How Code & Workflows Move)

Code and tasks progress in strictly one safe, orderly, verified direction:

```
[ Tier 1: Dev ]       ──►   [ Tier 2: Plan ]      ──►   [ Tier 3: Staging ]   ──►   [ Tier 4: GitHub ]    ──►   [ Tier 5: Google Drive ]
D:\Antigravity-Dev          Investigation & Plan        D:\GitHub\ReleaseSource     D:\GitHub\                  JavaFx / <Repo> Folder
Active coding,              End-to-End Analysis.        Sync on "approved".         PR, Auto-Merge, Tag         In-place synced clean
compile & test.             Zero edits without OK.      Local IntelliJ (.idea safe) on "push to github".        cloud project mirror.
```

### 1.1 Tier 1: Antigravity Active Dev Workspace (`D:\Antigravity-Dev\<Repo>`)
- The AI's active workshop where all active code editing, refactoring, unit tests, and compiler checks (`mvn clean test`, etc.) occur first.
- Zero extraneous files are permitted here.

### 1.2 Tier 2: Upfront Architectural Investigation & Root Cause Plan Phase
- Conducted strictly under Tier 1 workspace (`D:\Antigravity-Dev\<Repo>`).
- On every user request, bug report, or feature build, conduct an exhaustive end-to-end investigation across the entire stack:
  - **FXML:** Layouts, hierarchies, tables, buttons, responsive sizing.
  - **Controller:** State, event handlers, background task executors, CSS style class binding.
  - **DTO & Shared Contracts:** Calculation engines, payload shapes, serialization, validation.
  - **API & REST Endpoints:** Spring Boot `@RestController`, routes, query parameters, status codes.
  - **Server Services & Repositories:** Business logic, transactions, entity relationships, query performance.
  - **Database & Migrations:** PostgreSQL schema, Flyway migrations, constraints, foreign keys.
- Present a clear report containing:
  - **The Why (Root Cause):** Grounded in concrete code evidence.
  - **The How (Step-by-Step Plan):** Concrete modifications to apply.
  - **Impacted Files & Line Ranges:** Full inventory of all files touched.
  - **Multi-Hop Linkage Analysis:** Downstream ripple effect (Function A ──► Function B ──► Screens 1 through 10+).
  - **Explicit Zero-Regression Guarantee:** Verifiable safeguards proving existing functionality will not break.
- **STRICT BINDING CONSTRAINT:** **NEVER build, modify, or update code until the user has explicitly confirmed and approved the plan.**

### 1.3 Tier 3: User Local Approval Phase ("approved" / "apply fix")
- Triggered when the user confirms with **"approved"** (or requests local application).
- Apply the approved fix strictly in the Tier 1 workspace (`D:\Antigravity-Dev\<Repo>`).
- Run local compiler checks, unit/integration tests, and automated release gates (`python scripts/audit-release-gates.py`).
- Synchronize all local testing copies using `python scripts/sync-to-all.py --code --repo <Repo>`:
  - **Tier 2 (User's Protected IntelliJ Copy):** `D:\JavaProject\<Repo>` (IntelliJ `.idea` directory is strictly protected and never overwritten).
  - **Tier 3 (Verified Pre-Release Staging Mirror):** `D:\GitHub\ReleaseSource-<Repo>`.
- Provide the user with exact verification steps for local testing.

### 1.4 Tier 4: User GitHub Release Authorization ("approved and push to github" / "sync and release")
- Triggered when the user explicitly confirms **"approved and push to github"** (or "release the code").
- Ensure the fix is verified in Tier 1 and synced across Tier 2, Tier 3, and Tier 4 (`D:\GitHub\<Repo>`).
- In Tier 4 repository:
  - Create a release branch (`release-vXX.X.XX`).
  - Commit and push to remote.
  - Open a Pull Request (PR) to `main`.
  - Enable auto-merge (or merge once all PR CI checks turn GREEN).
  - Monitor `main` branch until CI workflow run is completely GREEN.
  - Create and push release tag (`vXX.X.XX`) on the verified green `main` commit to kick off the official release build.
  - Monitor and confirm the release build completes successfully.

### 1.5 Tier 5: Cloud Backup Mirror (Google Drive Integration)
- Keeps all 4 ecosystem repositories permanently backed up and synchronized to Google Drive in both **uncompressed source directory trees** (for mobile Gemini app direct reading and AI indexing) and **in-place clean snapshot archives (`.zip`)**.
- **Root Parent Folder (`JavaFx`):** `1Nn2wQWrNETBUNHbAAbYriWNeu_nXai7l` ([View on Drive](https://drive.google.com/drive/folders/1Nn2wQWrNETBUNHbAAbYriWNeu_nXai7l))
- **Registered Subfolders & In-Place Archive File IDs:**
  | Project Repository | Google Drive Subfolder ID | In-Place Archive File ID | Direct Drive Link |
  | :--- | :---: | :---: | :--- |
  | **`Jasvi-Desktop`** | `1gBQWeTodjuxQmUHuWzEiE-MoRRC5W7qA` | `1oOjUnxuiJhq5SV4iO5UHN1G-oYFEhNwX` | [Jasvi-Desktop Folder](https://drive.google.com/drive/folders/1gBQWeTodjuxQmUHuWzEiE-MoRRC5W7qA) |
  | **`Jasvi-Admin`** | `10fio8DAwYowAwVM5LyPhe877CXjy5OK3` | `13iMvdV7HqqC-whAknCXi6-rUHCvbOOWA` | [Jasvi-Admin Folder](https://drive.google.com/drive/folders/10fio8DAwYowAwVM5LyPhe877CXjy5OK3) |
  | **`Jasvi-UserView`**| `1W5yeaVOExL_k5wsYUxVepadRj_MocEcq` | `1cmVdRKGrM1FD5q2gk5GDo9mFj5XZUN9I` | [Jasvi-UserView Folder](https://drive.google.com/drive/folders/1W5yeaVOExL_k5wsYUxVepadRj_MocEcq) |
  | **`Jasvi-Mobile`** | `104QmrAHnLGHSMrn5zHBPeM-30ZMtOOo4` | `1BoGSpdDLZAREpW2Z5D1nwVHZHLu7fQi1` | [Jasvi-Mobile Folder](https://drive.google.com/drive/folders/104QmrAHnLGHSMrn5zHBPeM-30ZMtOOo4) |
- **Dual Cloud Sync Mechanism:**
  1. **Uncompressed Source Trees:** Uploads all source code files (`.java`, `.fxml`, `.html`, `.css`, `.js`, `.sql`, `.json`, `.md`, `.properties`, etc.) into nested folders mirroring the local repository structure with text MIME types, enabling the **Gemini mobile app** to directly read, search, and parse code on mobile devices. Excludes `.git/`, `.idea/`, `target/`, `build/`, `.gradle/`, and `postgresql/` binaries.
  2. **In-Place Snapshot Archive:** Automated via `python scripts/sync-to-all.py --gdrive` (or `--code --all --gdrive`), updating the root `.zip` file in-place without changing file IDs or breaking links.

### 1.6 Protected Local Runtime Workspace: `D:\DSE Production`
- Live PostgreSQL database (`port 55432`), configs, runtime logs, attachments, backups.
- **NEVER** run destructive SQL (`TRUNCATE`, `DROP`, `DELETE`) or alter database/runtime files here unless explicitly instructed.

---

## 2. The 15 Canonical Operating, Engineering & UI/UX Rules

### Rule 1: Zero Extraneous Files Across All Copies (No .txt, .log, arbitrary .md, defect dumps)
- **NEVER** create, add, or sync any extraneous file into ANY repository workspace or tier (`D:\Antigravity-Dev\`, `D:\JavaProject\`, `D:\GitHub\ReleaseSource-*`, `D:\GitHub\`).
- Forbidden files: No defect logs, no `.txt` scratch files, no arbitrary `.md` notes, no temporary checklists, no test dumps.
- ONLY files that are strictly an integral part of the application code itself are allowed.
- Whitelisted markdown files: `README.md`, `dse-erp-master-protocol.md`, `oracle.md`, `GEMINI.md`, `dynamic_showcase_architecture_spec.md`, `github-deployment-setup.md`, `rollback.md`, `release-notes.md`, and files under `docs/`.
- All temporary analysis, scratch notes, and investigation logs MUST be kept exclusively inside Antigravity's session artifact/scratch directory (`<appDataDir>\brain\...`), NEVER in the project workspaces.
- **Mandatory Universal Protocol Synchronization:** Whenever `dse-erp-master-protocol.md`, `oracle.md`, or `GEMINI.md` is updated, run `python scripts/sync-to-all.py` to synchronize across all 4 ecosystem repositories and tiers.

### Rule 2: Automatic Next-Release Version Targeting
- Once an application version is built, tagged, or live (e.g. `v11.0.37`), any subsequent user request, bug fix, or feature automatically targets the **NEXT incremental release version** (e.g. `v11.0.38`).
- Automatically manage version numbering in `.mvn/maven.config`, release notes in `release-notes.md`, and Flyway migration file prefixes (e.g. `V11_0_38__*.sql`).

### Rule 3: Cumulative Context & Multi-Question Synthesis (Zero Dropped Questions)
- When the user asks follow-up questions, adds constraints, or poses 10 to 20 inquiries across multiple turns, answering new questions must **NEVER** cause prior questions or findings to be lost.
- Responses must synthesize cumulative context, maintaining a unified summary of all previous inquiries and answers alongside the current request so that both parties remain 100% aligned.

### Rule 4: Modern Java 25 Standard
- Always leverage Java 25 latest syntax, idioms, pattern matching, records, virtual threads, modern switch expressions, sealed hierarchies, and stream enhancements.
- Code must be lean, deterministic, thread-safe, and free of obsolete legacy patterns.

### Rule 5: Universal Light & Dark Theme Parity & Zero Controller Color Leaks
- Every screen, component, modal dialog, and table must render flawlessly with full visual parity in both Light and Dark themes.
- **Zero Controller Color Leaks:** NEVER place hex colors (`#[0-9A-Fa-f]{6}`) or inline JavaFX color properties (`-fx-background-color`, `-fx-text-fill`, `-fx-icon-color`, `-fx-border-color`) inside Java controllers (`desktop/src/main/java/org/example/controller/`). Always use CSS classes (`getStyleClass().add(...)`).

### Rule 6: Colorized KPI Summary Cards
- Every KPI card across all screens must have its **Label**, **Icon**, and **Value** styled in semantic colors (using theme accent/success/warning/primary/info CSS classes).
- Plain, unstyled monochrome or muted grey text is strictly prohibited.

### Rule 7: Dynamic Responsive Table Headers
- Table columns must **NEVER** have hardcoded fixed widths (`prefWidth="..."`) in FXML.
- Dynamic width distribution / percentage allocation must be used (matching standard screen conventions) so columns scale cleanly with window resizing and avoid clipping or horizontal scrollbars.

### Rule 8: Colorized Action Lists & Context Menus
- All action lists, dropdown actions, table row context menus, and action drawers must have both **Icon** and **Label** rendered in semantic theme colors.

### Rule 9: Icon Uniformity & Reusability Across Identical Actions
- The same action (e.g. Edit, Delete, View, Print, Export, Refresh, Duplicate, Cancel) must reuse the exact same standardized icon across all screens and action menus.
- Never use disparate, conflicting, or non-standard icons for identical operations.

### Rule 10: Automation Gate Compliance & Continuous Self-Learning from CI
- Whenever buttons, FXML elements, or controllers change, all automation gates (`SharedUiOwnershipContractTest`, `audit-release-gates.py`, UI design system audits) must pass locally.
- **Mandatory Full Reactor Pre-Flight Test:** Never run tests on a single submodule alone (e.g. `-pl server`). Before opening any PR or pushing code to Tier 4, execute a full reactor test run (`.\mvnw.cmd test` or `./mvnw test` across all modules: parent, shared, server, desktop) and verify 0 failures and 0 errors.
- **Source-Scanning Contract Test Invariance:** Architecture contract tests (`SharedUiOwnershipContractTest`, `GlobalUiContractTest`, `UiArchitectureContractTest`) perform exact string pattern checks on Java source files. When modifying controllers or actions, maintain strict compatibility with the token patterns expected by the contract tests (e.g., whitespace and style class sequences).
- **Outbound HTTP / External Network Decoupling from Transactions:** Never invoke external third-party HTTP endpoints (such as WAHA WhatsApp or Mail server) inside a database `@Transactional` block. Auxiliary audit persistence must use `@Transactional(propagation = Propagation.REQUIRES_NEW)` so network failures or secondary log errors cannot roll back successful communication transmissions.
- **Continuous Self-Learning from CI:** If GitHub Actions CI ever rejects or fails a build:
  1. Immediately extract the failed step logs via `gh run view <run-id> --log-failed`.
  2. Pinpoint the root cause down to the exact test or assertion failure.
  3. Formulate the permanent prevention rule, update `dse-erp-master-protocol.md`, and synchronize to all repos and tiers via `python scripts/sync-to-all.py`.
  4. Adhere to the prevention rule permanently in all future turns.

### Rule 11: Colorized Status Labels and Table Cell Data
- All status badges, critical table cell data (e.g. amounts, balances, invoice status, dates, active/inactive), and key labels must use semantic colors defined by the theme CSS system for maximum contrast and visual hierarchy.

### Rule 12: Responsive Modal & Popup Window Sizing
- Dialog popups, alert boxes, and modal windows must have sufficient width and height (with appropriate `minWidth`, `minHeight`, and responsive bounds) so that text is never truncated, buttons are fully visible, and scrollbars do not cause clipped UI.

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

---

## 3. The "Desktop is King" Parity Rule (7-Point Golden Inspection)

Whenever implementing or debugging features on Web, UserView, or Mobile, Desktop is the golden reference:
1. **Layout Parity:** Mirror exact column orders, headers, dynamic widths, modals, and drawers.
2. **Screen Init:** Load exact master bootstrap data and default to past 6 months (`today.minusMonths(6)`).
3. **Calculation Engine:** Match `DocumentCalculationEngine.java` math, GST intra/inter splits, and `RoundingMode.HALF_UP`.
4. **REST Contracts:** Align 100% with Spring Boot `@RestController` DTOs and Bearer JWT auth.
5. **Database Models:** Match JPA entities and PostgreSQL table constraints.
6. **Input Events:** Auto-populate customer addresses, item rates, HSN codes, and tax rates on selection.
7. **Zero Mock Data:** Real database persistence and RFC 7807 (`ProblemDetail`) error handling only.

---

## 4. Cloud Infrastructure & Oracle OCI Architecture Reference

All Oracle Cloud Infrastructure (OCI), DNS records, IP addresses, systemd units, Caddy reverse proxy configs, and PostgreSQL tuning specifications have been permanently decoupled into:

👉 **[`oracle.md`](oracle.md)**

Refer to `oracle.md` for:
- Hostinger DNS Records (subdomains, target IPs, roles).
- OCI Server Specifications (PROD `130.210.35.1`, UAT `144.24.109.1`, RAM, Disk, SSH keys).
- Systemd Service Units (`dse-erp-prod.service`, `dse-erp-uat.service`).
- Caddy Reverse Proxy Configuration (`/etc/caddy/Caddyfile`).
- PostgreSQL OCI Tuning (`postgresql.conf`).
- Live health check, inspection, and service management commands.

---

## 5. Master Rollback History & Parity Reference
* **Core Git Tag:** `v11.0.0` (PR #70)
* **Web Git Tag:** `web-v11.0.0.01`
* **Current Production Version:** Governed by `.mvn/maven.config` and `desktop/src/main/resources/release-notes.md`.
