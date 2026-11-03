# Jasvi Ecosystem Master Protocol & Unified Environment Constitution

> **Single Source of Truth** for the unified Jasvi ERP ecosystem (`Jasvi-Desktop`, `Jasvi-Admin`, `Jasvi-UserView`, `Jasvi-Mobile`).  
> **Antigravity Enforcement:** This document is automatically loaded by Antigravity on every conversation. All rules, boundaries, and checklists herein are strictly binding.  
> **Last Updated:** 2026-11-03 — Simplified Executive Layout with Quick-Reference Architecture, Strict 5-Rule Engine, and Universal Multi-Tier Sync.

---

## ⚡ Quick-Reference Executive Summary (Read in 2 Minutes)

### 1. The 4 Applications at a Glance
All 4 client applications talk to **one shared Spring Boot backend** and **one PostgreSQL 16 database**:

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
| **`Jasvi-Desktop`** | JavaFX 25 + Spring Boot | Main enterprise workstation system (70 FXML pages) | Local Workstation | `ssdhameliya/Jasvi-Desktop` |
| **`Jasvi-Admin`** | Pure HTML5 / CSS3 / ES6 | Web ERP admin client for remote staff | `https://erp.jasviindustries.in` | `ssdhameliya/Jasvi-Admin` |
| **`Jasvi-UserView`**| HTML5 / Swiper / 3D | Public showcase, machine specifications, RFQs | `https://jasviindustries.in` | `ssdhameliya/Jasvi-UserView` |
| **`Jasvi-Mobile`** | Kotlin Multiplatform / PWA | Cross-platform mobile gateway | `https://mobile-uat.jasviindustries.in` | `ssdhameliya/Jasvi-Mobile` |

---

### 2. The 4-Tier Workspace Pipeline (How Code Moves)
Code moves in strictly one safe, orderly direction: **Left ──► Right**

```
[ Tier 1: Dev ]       ──►   [ Tier 2: IntelliJ ]  ──►   [ Tier 3: Staging ]   ──►   [ Tier 4: GitHub ]
D:\Antigravity-Dev          D:\JavaProject              D:\GitHub\ReleaseSource     D:\GitHub\
The AI's active             User's protected copy       Verified pre-release        Official Git remotes
coding workshop.            for local IntelliJ test.    snapshot.                   tracking main.
```

> [!CAUTION]
> **Protected Local Database:** `D:\DSE Production` (PostgreSQL on port `55432`).  
> Contains live local database, backups, attachments, and configs. **NEVER run destructive DDL/DML (`TRUNCATE`, `DROP`, `DELETE`) or wipe files here.**

---

### 3. The 2 Cloud Servers (PROD vs UAT)

| Environment | Role | Server IP | Domain Names | Deployment Rule |
| :--- | :--- | :--- | :--- | :--- |
| **UAT (Testing)** | Staging sandbox | `144.24.109.1` | `web-uat.jasviindustries.in`<br>`erp-uat.jasviindustries.in`<br>`api-uat.jasviindustries.in` | **Automatic:** Code pushed or merged to `main` deploys here immediately. |
| **PROD (Live)** | Real business operations | `130.210.35.1` | `jasviindustries.in`<br>`erp.jasviindustries.in`<br>`api.jasviindustries.in` | **100% Manual:** Never auto-deploys. Requires manual GitHub Actions dispatch. |

---

### 4. The 5 Canonical Operating Rules for AI

1. **Rule 1: Zero Extraneous Files Across All Copies**  
   Strictly no `.txt`, `.log`, or arbitrary `.md` files in any repository. Temporary notes belong exclusively in `<appDataDir>\brain\...`.
2. **Rule 2: No Code Changes or Builds Without Explicit Approval**  
   Never modify code or trigger builds on a request until the user explicitly confirms the proposed plan. Post-approval, execute directly with strict scope locking.
3. **Rule 3: Exhaustive Investigation, Multi-Hop Trace & Zero Assumptions**  
   Always trace the ripple effect (Function A ──► Function B ──► Screens 1..10+) and prove zero regression before planning.
4. **Rule 4: UserView Push = UAT Only, PROD is 100% Manual**  
   Production site remains locked to `upcoming.html` until manually launched by the user.
5. **Rule 5: Mandatory Default Plugin Quality Gate**  
   Run all 5 checks (Web Standards, Security, Lean Code, Vendor Health, Desktop Theme/Flyway Version) on every proposal.

---

### 5. The "Desktop is King" Parity Rule (7-Point Inspection)
Whenever implementing or debugging features on Web, UserView, or Mobile, Desktop is the golden reference:
1. **Layout Parity:** Mirror exact column orders, headers, widths, modals, and drawers.
2. **Screen Init:** Load exact master bootstrap data and default to past 6 months (`today.minusMonths(6)`).
3. **Calculation Engine:** Match `DocumentCalculationEngine.java` math, GST intra/inter splits, and `RoundingMode.HALF_UP`.
4. **REST Contracts:** Align 100% with Spring Boot `@RestController` DTOs and Bearer JWT auth.
5. **Database Models:** Match JPA entities and PostgreSQL table constraints.
6. **Input Events:** Auto-populate customer addresses, item rates, HSN codes, and tax rates on selection.
7. **Zero Mock Data:** Real database persistence and RFC 7807 (`ProblemDetail`) error handling only.

---

## 1. Workspace Directory Map & Strict Protection Boundaries

### 1.1 Desktop & Server Workspaces (`Jasvi-Desktop`)
1. **Tier 1 (Antigravity Active Dev):** `D:\Antigravity-Dev\Jasvi-Desktop`  
   *All active coding, refactoring, compiler checks, and tests are performed here first.*
2. **Tier 2 (User Protected IntelliJ Copy):** `D:\JavaProject\Jasvi-Desktop`  
   *Reserved exclusively for local IntelliJ workflow (`.idea` strictly protected). Synced only upon explicit user command ("ready to test for local" or PR preparation).*
3. **Tier 3 (Verified Staging Mirror):** `D:\GitHub\ReleaseSource-Desktop`  
   *Pre-release staging snapshot verified before pushing.*
4. **Tier 4 (GitHub Target Repository):** `D:\GitHub\Jasvi-Desktop` (`https://github.com/ssdhameliya/Jasvi-Desktop`)
5. **Local Runtime Database (Protected):** `D:\DSE Production` (Port `55432`).

### 1.2 Web Admin Client Workspaces (`Jasvi-Admin`)
1. **Tier 1:** `D:\Antigravity-Dev\Jasvi-Admin` *(Single source of truth for Web ERP)*
2. **Tier 2:** `D:\JavaProject\Jasvi-Admin`
3. **Tier 3:** `D:\GitHub\ReleaseSource-Admin`
4. **Tier 4:** `D:\GitHub\Jasvi-Admin` (`web/admin` directory on branch `main`)
5. **Live UAT URL:** `https://erp-uat.jasviindustries.in/`
6. **Live PROD URL:** `https://erp.jasviindustries.in/`

### 1.3 Public UserView Workspaces (`Jasvi-UserView`)
1. **Tier 1:** `D:\Antigravity-Dev\Jasvi-UserView` *(Single source of truth for 3D showcase & RFQ portal)*
2. **Tier 2:** `D:\JavaProject\Jasvi-UserView`
3. **Tier 3:** `D:\GitHub\ReleaseSource-UserView`
4. **Tier 4:** `D:\GitHub\Jasvi-UserView` (`https://github.com/ssdhameliya/Jasvi-UserView`)
5. **Live UAT URL:** `https://web-uat.jasviindustries.in/`
6. **Live PROD URLs:** `https://jasviindustries.in/` & `https://www.jasviindustries.in/`

### 1.4 Cross-Platform Mobile Workspaces (`Jasvi-Mobile`)
1. **Tier 1:** `D:\Antigravity-Dev\Jasvi-Mobile` *(KMP / Compose Multiplatform & PWA)*
2. **Tier 2:** `D:\JavaProject\Jasvi-Mobile`
3. **Tier 3:** `D:\GitHub\ReleaseSource-Mobile`
4. **Tier 4:** `D:\GitHub\Jasvi-Mobile` (`https://github.com/ssdhameliya/Jasvi-Mobile`)
5. **Live UAT Gateway:** `https://mobile-uat.jasviindustries.in/`

### 1.5 Cleanliness & Universal Protocol Synchronization
* **Whitelisted `.md` Files Only:** `README.md`, `dse-erp-master-protocol.md`, `desktop/src/main/resources/release-notes.md`, and application docs in `docs/`. All other `.txt`, `.log`, and `.md` files are strictly banned from repos.
* **Mandatory Universal Protocol Synchronization:** Whenever `dse-erp-master-protocol.md` or `GEMINI.md` is updated, Antigravity MUST automatically synchronize the updated file across ALL 4 ecosystem repositories and tiers:
  * `D:\Antigravity-Dev\<Repo>`
  * `D:\JavaProject\<Repo>`
  * `D:\GitHub\<Repo>`
  * `D:\GitHub\ReleaseSource-*`
  * Global Config: `C:\Users\JATIN DHAMELIYA\.gemini\config\rules\dse-erp-master-protocol.md`

---

## 2. Server Infrastructure, DNS & Environment Inventory

### 2.1 Hostinger DNS Records (Domain: `jasviindustries.in`)
| Domain / Subdomain | Target IP / Host | Environment | Destination / Role | Source Repository |
| :--- | :--- | :---: | :--- | :--- |
| **`jasviindustries.in`** | `130.210.35.1` | **PROD** | Public 3D Showcase / Root (`/srv/dse-erp/web/public`) | `ssdhameliya/Jasvi-UserView` |
| **`www.jasviindustries.in`** | `130.210.35.1` | **PROD** | Public 3D Showcase (CNAME to `@`) | `ssdhameliya/Jasvi-UserView` |
| **`erp.jasviindustries.in`** | `130.210.35.1` | **PROD** | Production Web ERP Admin Studio (`/srv/dse-erp/web/admin`) | `ssdhameliya/Jasvi-Admin` |
| **`api.jasviindustries.in`** | `130.210.35.1` | **PROD** | Production REST Backend API (Port `8080`) | `ssdhameliya/Jasvi-Desktop` |
| **`web-uat.jasviindustries.in`** | `144.24.109.1` | **UAT** | UAT Public 3D Showcase Staging (`/srv/dse-erp/web/public`) | `ssdhameliya/Jasvi-UserView` |
| **`erp-uat.jasviindustries.in`** | `144.24.109.1` | **UAT** | UAT Web ERP Testing Studio (`/srv/dse-erp/web/admin`) | `ssdhameliya/Jasvi-Admin` |
| **`api-uat.jasviindustries.in`** | `144.24.109.1` | **UAT** | UAT REST Backend API (Port `8081`) | `ssdhameliya/Jasvi-Desktop` |
| **`mobile-uat.jasviindustries.in`**| `ssdhameliya.github.io` | **UAT** | Mobile PWA Gateway | `ssdhameliya/Jasvi-Mobile` |

### 2.2 Oracle Cloud (OCI) Server Specification & Paths
| Parameter | Production (PROD) | Testing (UAT) |
| :--- | :--- | :--- |
| **Server Public IP** | `130.210.35.1` | `144.24.109.1` |
| **SSH User** | `opc` | `opc` |
| **SSH Private Key (Local)** | `~/.ssh/dse-erp-github-prod-ci` | `~/.ssh/dse-erp-github-uat` |
| **Total Memory (RAM)** | **8 GB** (`6.7 GiB` usable, `4.0 GiB` Swap) | **4 GB** (`3.6 GiB` usable, `4.0 GiB` Swap) |
| **Physical Block Volume** | **130 GB** (Expanded Boot Volume) | **70 GB** (Expanded Boot Volume) |
| **Root Partition (`/`)** | **113 GB** XFS (`ocivolume-root`, ~94 GB free) | **53 GB** XFS (`ocivolume-root`, ~33 GB free) |
| **Auxiliary Partitions** | `15 GB` (`/var/oled`), `2 GB` (`/boot`) | `15 GB` (`/var/oled`), `2 GB` (`/boot`) |
| **Spring Server Port** | `8080` | `8081` |
| **Target Database** | `dse_erp` (PostgreSQL 16+) | `dse_erp_uat` (PostgreSQL 16+) |
| **Public Showcase Root** | `/srv/dse-erp/web/public` | `/srv/dse-erp/web/public` |
| **Web ERP Root** | `/srv/dse-erp/web/admin` | `/srv/dse-erp/web/admin` |
| **Web Server / SSL** | Caddy (Auto TLS, HTTP/3, Static `file_server` + `/api/*` reverse proxy) | Caddy (Auto TLS, HTTP/3, Static `file_server` + `/api/*` reverse proxy) |

#### Remote Server Health Commands
```powershell
# Windows PowerShell - Check Production (RAM & Disk)
ssh -i "$HOME/.ssh/dse-erp-github-prod-ci" opc@130.210.35.1 "free -h; echo '--- STORAGE ---'; df -h /"

# Windows PowerShell - Check UAT (RAM & Disk)
ssh -i "$HOME/.ssh/dse-erp-github-uat" opc@144.24.109.1 "free -h; echo '--- STORAGE ---'; df -h /"
```

---

## 3. Decoupled Multi-Platform Architecture

The backend server is a stateless, high-performance Spring Boot REST API. Desktop, Web, UserView, and Mobile clients connect via REST endpoints with Bearer JWT authentication:

```
[ JavaFX Desktop ]   [ Web Admin Client ]   [ UserView 3D Showcase ]   [ KMP Mobile App ]
70 FXML Pages        MVC SPA (Jasvi-Admin)  Dynamic 3D Portal          PWA / Android
Local Workstation    Caddy on OCI (Static)  Caddy on OCI (Static)      GitHub Pages
       │                     │                        │                       │
       └─────────────────────┼────────────────────────┴───────────────────────┘
                             ▼
              [ Spring Boot REST Backend Server ]
              Port 8080 (PROD) / 8081 (UAT)
              Stateless JWT SecurityConfig (/api/**)
              227 Business Services & Repositories
                             ▼
              [ Shared Pure Java Contracts ]
              DocumentCalculationEngine, PermissionCatalog,
              AmountInWordsConverter, Code128Encoder
                             ▼
              [ PostgreSQL 16 Relational Database ]
              dse_erp (PROD) / dse_erp_uat (UAT)
```

---

## 4. Mandatory 7-Point First-Principle Inspection Checklist

> **"Each and everything means each and everything."** Never build half-finished screens, never guess formulas, and **NEVER use mock or dummy data.**

### Point 1: Front-End & User Interface Structure
* Mirror exact layouts from desktop FXML/Controller (`desktop/src/main/resources/fxml/pages/`): tables, column ordering, headers, alignments, widths, summary cards, status badges, action buttons, side drawers, modals, and tabs.

### Point 2: Default Screen Open & Initialization Logic
* **Bootstrap APIs:** Determine master data loaded on init (e.g. `GET /api/master/sales-entry-bootstrap`).
* **Default Filters:** Apply Desktop defaults: past 6 months (`today.minusMonths(6)` to `today`), active status filters, next sequence number generation, and default focus.

### Point 3: Dynamic Logic & Calculation Engines
* Inspect `DocumentCalculationEngine.java` in `shared/src/main/java/org/example/shared/`:
  * **Item Taxable:** `Quantity * Rate * (1 - Discount% / 100)`
  * **GST Splitting:** Intra-state (`GST`) -> `CGST = Round(Tax / 2)`, `SGST = Tax - CGST`. Inter-state (`IGST`) -> `IGST = Tax`, `CGST = 0`, `SGST = 0`.
  * **Rounding:** Exact decimal scales (2 decimals for currency, 4 for quantity/unit cost) using `RoundingMode.HALF_UP`.
  * **Grand Total:** `Item Taxable + Line Tax + Charges Amount + Charges Tax`.

### Point 4: API Endpoints, Payloads & DTO Contracts
* Inspect Spring Boot `@RestController` and DTOs in `server/src/main/java/org/example/server/`.
* Verify exact URL paths, HTTP verbs (`GET`, `POST`, `PUT`, `DELETE`), query parameters, and JSON payload keys match 100%.

### Point 5: Database Schema & Master Data Models
* Inspect JPA entities and repositories (`LookupMaster`, `PartyMaster`, `ItemMaster`, `sales_header`, `purchase_header`, `payment_record`).
* Verify dropdown categories (`TRANSPORTER`, `CHARGE_TYPE`, `PAYMENT_TERMS`, `UNIT`, `PAYMENT_MODE`, `BANK_ACCOUNT`).

### Point 6: UI Behaviors for Every Single Event
* **Auto-population:** Select Customer -> Billing/Shipping Address, GSTIN, State Code, Payment Terms. Select Item -> HSN/SAC, Unit, Sell Rate, Tax Rate.
* **Secondary Drawers/Dialogs:** Additional Charges dialog, Action Drawers (View, Audit, Edit, Duplicate, Print, Barcode, Excel, Payment, Cancel, Delete).

### Point 7: Functional Completeness & Error Handling
* Saving must persist real database records via the API.
* **RFC 7807 Compliance:** All API errors return `ProblemDetail` with `Content-Type: application/problem+json`.
* **Zero Mock Fallbacks:** Never return fake fallback arrays or dummy values.

---

## 5. Version Management & Release Automation Scripts

### 5.1 Version Governance Policy
* **Desktop / Server Version:** Governed strictly by `.mvn/maven.config` (`-Drevision=11.0.X`).
* **Web Admin Client Version:** Governed by `admin/version.json` and `admin/js/version.js` (`11.0.X.YY`, tag `web-v11.0.X.YY`).
* **UserView Showcase Version:** Governed by git tags in `ssdhameliya/Jasvi-UserView` (`showcase-v1.0.0`) and `data/showcase.json`.
* **Mobile Client Version:** Governed by `MobileBuildInfo.kt` and git tags in `ssdhameliya/Jasvi-Mobile`.
* **Release Notes (Single Source of Truth):** Every release must update `desktop/src/main/resources/release-notes.md`.

---

### 5.2 PowerShell Script 1: Desktop/Server Release & PR Automation
```powershell
$Version = "11.0.24"
$Note    = "Enterprise Release"

$Repo    = "ssdhameliya/Jasvi-Desktop"
$Branch  = "release-$Version"
$Project = "D:\GitHub\Jasvi-Desktop"
$Source  = "D:\GitHub\ReleaseSource-Desktop"

$env:GIT_PAGER = "cat"
$env:GH_PAGER  = "cat"

function Check($Message) {
    if ($LASTEXITCODE -ne 0) { throw "STOP: $Message" }
}

Set-Location $Project

# 1. Clean main
git switch main; Check "Could not switch to main."
git fetch origin --prune; Check "Git fetch failed."
git reset --hard origin/main; Check "Could not reset main."

# 2. Remove old PR / Release branch
$OldPr = gh pr list --repo $Repo --head $Branch --state open --json number --jq '.[0].number'
if ($OldPr) { gh pr close $OldPr --repo $Repo }
if (git branch --list $Branch) { git branch -D $Branch }
git ls-remote --exit-code --heads origin $Branch *> $null
if ($LASTEXITCODE -eq 0) { git push origin --delete $Branch }

# 3. Create fresh release branch + copy source
git switch -c $Branch origin/main; Check "Could not create $Branch."
robocopy $Source $Project /MIR /COPY:DAT /R:1 /W:1 /XD .git target .idea
if ($LASTEXITCODE -gt 7) { throw "STOP: Robocopy failed with code $LASTEXITCODE." }

# 4. Verify version
$Revision = (Get-Content .mvn\maven.config).Trim()
if ($Revision -ne "-Drevision=$Version") { throw "STOP: Expected -Drevision=$Version but found '$Revision'." }

# 5. Full build + release audit
.\mvnw.cmd clean test -pl server -am; Check "Server regression tests failed."
python scripts/audit-release-gates.py; Check "Release audit failed."

# 6. Commit & Push
git add -A
git commit -m "Release v$Version - $Note"; Check "Commit failed."
git push -u origin $Branch; Check "GitHub push failed."

# 7. Create PR
gh pr create --repo $Repo --base main --head $Branch --title "Release v$Version - $Note" --body @"
Release v$Version: $Note
Validation: .\mvnw.cmd clean test -pl server -am and scripts/audit-release-gates.py passed.
"@
gh pr view $Branch --repo $Repo --web

# 8. Sync User's Local IntelliJ Workspace
$LocalUserCopy = "D:\JavaProject\Jasvi-Desktop"
robocopy $Source $LocalUserCopy /MIR /COPY:DAT /R:1 /W:1 /XD .git target .idea
Write-Host "Synchronized $Source -> $LocalUserCopy (Robocopy Exit Code: $LASTEXITCODE)"
```

---

### 5.3 PowerShell Script 2: Web Admin Release Script (`Jasvi-Admin`)
```powershell
$WebVersion = "11.0.24.01" # Format: <DesktopVersion>.<WebBuild: 2 digits>
$Tag        = "web-v$WebVersion"
$Note       = "Enterprise Web Client Release"

$SourceDir  = "D:\Antigravity-Dev\Jasvi-Admin"
$StagingDir = "D:\GitHub\ReleaseSource-Admin"
$GitTarget  = "D:\GitHub\Jasvi-Admin"
$UserCopy   = "D:\JavaProject\Jasvi-Admin"

# 1. Sync Dev to Staging Mirror
robocopy $SourceDir $StagingDir /MIR /COPY:DAT /R:1 /W:1 /XD .git .idea
if ($LASTEXITCODE -gt 7) { throw "Robocopy to staging failed" }

# 2. Sync Staging to User IntelliJ Copy
robocopy $StagingDir $UserCopy /MIR /COPY:DAT /R:1 /W:1 /XD .git .idea
if ($LASTEXITCODE -gt 7) { throw "Robocopy to user copy failed" }

# 3. Sync Staging to Git Target Repository web/admin
robocopy $StagingDir "$GitTarget\web\admin" /MIR /COPY:DAT /R:1 /W:1 /XD .git .idea
if ($LASTEXITCODE -gt 7) { throw "Robocopy to git target failed" }

# 4. Commit, tag, and push in GitHub repo
Set-Location $GitTarget
git switch main; git pull origin main
git add web/admin .github/workflows
git commit -m "Web Release $Tag: $Note"
git tag -f $Tag -m "Release $Tag - $Note"
git push origin main
git push -f origin $Tag

Write-Host "Triggered automated deployment to UAT and Production via GitHub Actions."
```

---

### 5.4 PowerShell Script 3: UserView Showcase Release Script (`Jasvi-UserView`)
```powershell
$Tag  = "showcase-v1.0.0"
$Note = "Dynamic 3D UserView Showcase Release"

$SourceDir  = "D:\Antigravity-Dev\Jasvi-UserView"
$StagingDir = "D:\GitHub\ReleaseSource-UserView"
$GitTarget  = "D:\GitHub\Jasvi-UserView"
$UserCopy   = "D:\JavaProject\Jasvi-UserView"

# 1. Sync Dev to Staging Mirror
robocopy $SourceDir $StagingDir /MIR /COPY:DAT /R:1 /W:1 /XD .git .idea
if ($LASTEXITCODE -gt 7) { throw "Robocopy to staging failed" }

# 2. Sync Staging to User IntelliJ Copy
robocopy $StagingDir $UserCopy /MIR /COPY:DAT /R:1 /W:1 /XD .git .idea
if ($LASTEXITCODE -gt 7) { throw "Robocopy to user copy failed" }

# 3. Sync Staging to Git Target Repository
robocopy $StagingDir $GitTarget /MIR /COPY:DAT /R:1 /W:1 /XD .git .idea .github
if ($LASTEXITCODE -gt 7) { throw "Robocopy to git target failed" }

# 4. Commit, tag, and push to GitHub
Set-Location $GitTarget
git switch main; git pull origin main
git add -A
git commit -m "Release $Tag: $Note"
git tag -f $Tag -m "Release $Tag - $Note"
git push -u origin main
git push -f origin $Tag

Write-Host "Live UAT:  https://web-uat.jasviindustries.in/ (Auto-deployed on push)"
Write-Host "Live PROD: https://jasviindustries.in/ (MANUAL dispatch required in GitHub Actions)"
```

---

### 5.5 PowerShell Script 4: Mobile Release Script (`Jasvi-Mobile`)
```powershell
$Version = "1.2.8"
$Tag     = "mobile-v$Version"
$Note    = "Jasvi Mobile Suite Release"

$SourceDir  = "D:\Antigravity-Dev\Jasvi-Mobile"
$StagingDir = "D:\GitHub\ReleaseSource-Mobile"
$GitTarget  = "D:\GitHub\Jasvi-Mobile"
$UserCopy   = "D:\JavaProject\Jasvi-Mobile"

# 1. Sync Dev to Staging Mirror
robocopy $SourceDir $StagingDir /MIR /COPY:DAT /R:1 /W:1 /XD .git .idea
if ($LASTEXITCODE -gt 7) { throw "Robocopy to staging failed" }

# 2. Sync Staging to User IntelliJ Copy
robocopy $StagingDir $UserCopy /MIR /COPY:DAT /R:1 /W:1 /XD .git .idea
if ($LASTEXITCODE -gt 7) { throw "Robocopy to user copy failed" }

# 3. Sync Staging to Git Target Repository
robocopy $StagingDir $GitTarget /MIR /COPY:DAT /R:1 /W:1 /XD .git .idea .github
if ($LASTEXITCODE -gt 7) { throw "Robocopy to git target failed" }

# 4. Commit, tag, and push to GitHub
Set-Location $GitTarget
git switch main; git pull origin main
git add -A
git commit -m "Release $Tag: $Note"
git tag -f $Tag -m "Release $Tag - $Note"
git push -u origin main
git push -f origin $Tag

Write-Host "Live Gateway: https://mobile-uat.jasviindustries.in/"
```

---

## 6. Cloud Deployment & OCI Production Reference

### 6.1 Systemd Service Unit (`/etc/systemd/system/dse-erp-prod.service`)
```ini
[Unit]
Description=DSE-ERP Cloud Platform Service (Spring Boot REST API)
After=network.target postgresql.service
Wants=postgresql.service

[Service]
Type=simple
User=opc
Group=opc
WorkingDirectory=/srv/dse-erp
ExecStart=/usr/bin/java \
    -XX:+UseZGC \
    -XX:+ZGenerational \
    -Xms2g -Xmx6g \
    -Dspring.profiles.active=prod \
    -Dfile.encoding=UTF-8 \
    -jar /srv/dse-erp/dse-erp-server.jar
Restart=always
RestartSec=10
LimitNOFILE=65536
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

### 6.2 Caddy Reverse Proxy Configuration (`/etc/caddy/Caddyfile`)
```caddy
# Public Company Showcase
jasviindustries.in, www.jasviindustries.in {
    root * /srv/dse-erp/web/public
    file_server
    encode gzip zstd
}

# Production Web ERP Admin Studio & REST API Proxy
erp.jasviindustries.in {
    root * /srv/dse-erp/web/admin
    encode gzip zstd
    file_server

    handle /api/* {
        reverse_proxy 127.0.0.1:8080 {
            header_up Host {host}
            header_up X-Real-IP {remote_host}
            header_up X-Forwarded-For {remote_host}
            header_up X-Forwarded-Proto {scheme}
        }
    }

    try_files {path} {path}/ /index.html
}

# Production Standalone Backend REST API
api.jasviindustries.in {
    reverse_proxy 127.0.0.1:8080
    encode gzip zstd
}

# UAT Web ERP Testing Studio & REST API Proxy
erp-uat.jasviindustries.in {
    root * /srv/dse-erp/web/admin
    encode gzip zstd
    file_server

    handle /api/* {
        reverse_proxy 127.0.0.1:8081 {
            header_up Host {host}
            header_up X-Real-IP {remote_host}
            header_up X-Forwarded-For {remote_host}
            header_up X-Forwarded-Proto {scheme}
        }
    }

    try_files {path} {path}/ /index.html
}

# UAT Standalone Backend REST API
api-uat.jasviindustries.in {
    reverse_proxy 127.0.0.1:8081
    encode gzip zstd
}
```

### 6.3 PostgreSQL OCI Production Tuning (`postgresql.conf`)
* **`shared_buffers`**: `2560MB` (25% of total RAM on 12GB instances)
* **`effective_cache_size`**: `6GB` (50–75% of total RAM)
* **`work_mem`**: `16MB`
* **`maintenance_work_mem`**: `512MB`
* **`listen_addresses`**: `'127.0.0.1'` (Loopback-only binding; protected behind local firewall/proxy)
* **`max_connections`**: `100`

---

## 7. Master Rollback History & Parity Audit Reference
* **Core Git Tag:** `v11.0.0` (PR #70)
* **Web Git Tag:** `web-v11.0.0.01`
* **Live Status:** Verified healthy across all production and UAT endpoints.