# Jasvi ERP Cloud Infrastructure, Network & Oracle OCI Specification

> **Single Source of Truth** for Cloud Hosting, Oracle Cloud Infrastructure (OCI), DNS Records, Reverse Proxy, Systemd Services, and Server Operations across Production (PROD) and Testing (UAT).  
> **Last Updated:** 2026-11-04  

---

## 1. Domain & DNS Configuration (Hostinger)

**Primary Domain:** `jasviindustries.in`  
Managed via Hostinger DNS Zone Management.

| Domain / Subdomain | Target IP / Host | Environment | Destination / Role | Source Repository |
| :--- | :--- | :---: | :--- | :--- |
| **`jasviindustries.in`** | `130.210.35.1` | **PROD** | Public 3D Showcase Root (`/srv/dse-erp/web/public`) | `ssdhameliya/Jasvi-UserView` |
| **`www.jasviindustries.in`** | `130.210.35.1` | **PROD** | Public 3D Showcase (CNAME to `@`) | `ssdhameliya/Jasvi-UserView` |
| **`erp.jasviindustries.in`** | `130.210.35.1` | **PROD** | Production Web ERP Admin Studio (`/srv/dse-erp/web/admin`) | `ssdhameliya/Jasvi-Admin` |
| **`api.jasviindustries.in`** | `130.210.35.1` | **PROD** | Production REST Backend API (Port `8080`) | `ssdhameliya/Jasvi-Desktop` |
| **`web-uat.jasviindustries.in`** | `144.24.109.1` | **UAT** | UAT Public 3D Showcase Staging (`/srv/dse-erp/web/public`) | `ssdhameliya/Jasvi-UserView` |
| **`erp-uat.jasviindustries.in`** | `144.24.109.1` | **UAT** | UAT Web ERP Testing Studio (`/srv/dse-erp/web/admin`) | `ssdhameliya/Jasvi-Admin` |
| **`api-uat.jasviindustries.in`** | `144.24.109.1` | **UAT** | UAT REST Backend API (Port `8081`) | `ssdhameliya/Jasvi-Desktop` |
| **`mobile-uat.jasviindustries.in`**| `ssdhameliya.github.io` | **UAT** | Mobile PWA Gateway | `ssdhameliya/Jasvi-Mobile` |

---

## 2. Oracle Cloud (OCI) Server Specifications & Storage Layout

| Parameter | Production (PROD) | Testing (UAT) |
| :--- | :--- | :--- |
| **Server Public IP** | `130.210.35.1` | `144.24.109.1` |
| **Operating System** | Oracle Linux 9 (x86_64 / aarch64) | Oracle Linux 9 (x86_64 / aarch64) |
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

### Remote Server Health & Storage Commands
```powershell
# Windows PowerShell - Check Production (RAM & Disk)
ssh -i "$HOME/.ssh/dse-erp-github-prod-ci" opc@130.210.35.1 "free -h; echo '--- STORAGE ---'; df -h /"

# Windows PowerShell - Check UAT (RAM & Disk)
ssh -i "$HOME/.ssh/dse-erp-github-uat" opc@144.24.109.1 "free -h; echo '--- STORAGE ---'; df -h /"
```

---

## 3. Systemd Service Units

### 3.1 Production Service Unit (`/etc/systemd/system/dse-erp-prod.service`)
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

### 3.2 UAT Service Unit (`/etc/systemd/system/dse-erp-uat.service`)
```ini
[Unit]
Description=DSE-ERP Cloud Platform Testing Service (Spring Boot REST API)
After=network.target postgresql.service
Wants=postgresql.service

[Service]
Type=simple
User=opc
Group=opc
WorkingDirectory=/srv/dse-erp-uat
ExecStart=/usr/bin/java \
    -XX:+UseZGC \
    -XX:+ZGenerational \
    -Xms1g -Xmx3g \
    -Dspring.profiles.active=uat \
    -Dserver.port=8081 \
    -Dfile.encoding=UTF-8 \
    -jar /srv/dse-erp-uat/dse-erp-server.jar
Restart=always
RestartSec=10
LimitNOFILE=65536
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

---

## 4. Caddy Reverse Proxy Configuration (`/etc/caddy/Caddyfile`)

```caddy
# Public Company Showcase (PROD)
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

---

## 5. PostgreSQL OCI Database Tuning (`postgresql.conf`)

* **`shared_buffers`**: `2560MB` (25% of total RAM on 12GB instances)
* **`effective_cache_size`**: `6GB` (50–75% of total RAM)
* **`work_mem`**: `16MB`
* **`maintenance_work_mem`**: `512MB`
* **`listen_addresses`**: `'127.0.0.1'` (Loopback-only binding; protected behind local firewall/proxy)
* **`max_connections`**: `100`

---

## 6. Remote Inspection & Maintenance Commands

### Check Service Status & Logs
```bash
# Check Production Service
sudo systemctl status dse-erp-prod.service

# Tail Live Server Logs
sudo journalctl -u dse-erp-prod.service -f --no-tail -n 100

# Restart Production Service Safely
sudo systemctl restart dse-erp-prod.service

# Check Caddy Web Server
sudo systemctl status caddy
sudo journalctl -u caddy -f -n 50
```

### PostgreSQL Local Connection (Loopback only)
```bash
# Connect as postgres superuser
sudo -u postgres psql -d dse_erp

# Check Database Size
sudo -u postgres psql -d dse_erp -c "SELECT pg_size_pretty(pg_database_size('dse_erp'));"
```
