package org.example.server.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Public landing controller for the Enterprise Workstation Remote Gateway.
 * Provides a hardened, neutral status screen when accessed via a web browser
 * through the Cloudflare HTTPS Tunnel, eliminating confusing 401 JSON errors
 * while guaranteeing ZERO information leakage of personal URLs or internal database details.
 */
@RestController
public class GatewayLandingController {

    private final RuntimeService runtimeService;
    private final String version;
    private final String environment;

    public GatewayLandingController(
            RuntimeService runtimeService,
            @Value("${dse.app.version:DEV}") String version,
            @Value("${dse.deployment.environment:LOCAL}") String environment) {
        this.runtimeService = runtimeService;
        this.version = version;
        this.environment = environment;
    }

    @GetMapping(value = {"/", "/gateway"}, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> renderGatewayDashboard() {
        boolean dbOk = false;
        try {
            dbOk = runtimeService.databaseReady();
        } catch (Exception ignored) {
        }

        String nowFormatted = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
                .format(DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm:ss a (z)"));

        String html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Enterprise Workstation Remote Gateway</title>
                <link rel="icon" href="data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='%233b82f6'><path d='M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4z'/></svg>">
                <style>
                    :root {
                        --bg: #0b1120;
                        --card-bg: rgba(30, 41, 59, 0.7);
                        --card-border: rgba(51, 65, 85, 0.8);
                        --text-primary: #f8fafc;
                        --text-secondary: #94a3b8;
                        --accent-blue: #3b82f6;
                        --accent-emerald: #10b981;
                        --accent-cyan: #06b6d4;
                    }
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
                        background: radial-gradient(circle at 50% 0%, #1e293b 0%, #0b1120 75%);
                        color: var(--text-primary);
                        min-height: 100vh;
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                        justify-content: center;
                        padding: 24px;
                    }
                    .container {
                        max-width: 780px;
                        width: 100%;
                    }
                    .header-card {
                        background: var(--card-bg);
                        border: 1px solid var(--card-border);
                        backdrop-filter: blur(16px);
                        border-radius: 16px;
                        padding: 32px;
                        margin-bottom: 24px;
                        box-shadow: 0 20px 40px -15px rgba(0,0,0,0.5);
                        position: relative;
                        overflow: hidden;
                    }
                    .header-accent {
                        position: absolute;
                        top: 0;
                        left: 0;
                        right: 0;
                        height: 4px;
                        background: linear-gradient(90deg, var(--accent-blue), var(--accent-cyan), var(--accent-emerald));
                    }
                    .header-top {
                        display: flex;
                        align-items: center;
                        justify-content: space-between;
                        flex-wrap: wrap;
                        gap: 16px;
                    }
                    .brand {
                        display: flex;
                        align-items: center;
                        gap: 14px;
                    }
                    .brand-icon {
                        width: 44px;
                        height: 44px;
                        border-radius: 12px;
                        background: linear-gradient(135deg, rgba(59,130,246,0.2), rgba(16,185,129,0.2));
                        border: 1px solid rgba(59,130,246,0.4);
                        display: flex;
                        align-items: center;
                        justify-content: center;
                    }
                    .brand-title {
                        font-size: 22px;
                        font-weight: 700;
                        letter-spacing: -0.5px;
                        color: #ffffff;
                    }
                    .brand-subtitle {
                        font-size: 13px;
                        color: var(--text-secondary);
                        margin-top: 2px;
                    }
                    .status-badge {
                        display: inline-flex;
                        align-items: center;
                        gap: 8px;
                        padding: 6px 14px;
                        border-radius: 9999px;
                        font-size: 12px;
                        font-weight: 600;
                        letter-spacing: 0.5px;
                        background: rgba(16, 185, 129, 0.15);
                        border: 1px solid rgba(16, 185, 129, 0.4);
                        color: #34d399;
                    }
                    .status-dot {
                        width: 8px;
                        height: 8px;
                        border-radius: 50%;
                        background: #10b981;
                        box-shadow: 0 0 10px #10b981;
                        animation: pulse 2s infinite;
                    }
                    @keyframes pulse {
                        0%, 100% { opacity: 1; transform: scale(1); }
                        50% { opacity: 0.5; transform: scale(0.85); }
                    }
                    .info-card {
                        background: var(--card-bg);
                        border: 1px solid var(--card-border);
                        backdrop-filter: blur(12px);
                        border-radius: 14px;
                        padding: 24px;
                        margin-bottom: 24px;
                    }
                    .card-kicker {
                        font-size: 11px;
                        font-weight: 700;
                        text-transform: uppercase;
                        letter-spacing: 1px;
                        color: var(--accent-cyan);
                        margin-bottom: 12px;
                    }
                    .metric-row {
                        display: flex;
                        justify-content: space-between;
                        padding: 8px 0;
                        border-bottom: 1px solid rgba(255,255,255,0.05);
                        font-size: 13.5px;
                    }
                    .metric-row:last-child { border-bottom: none; }
                    .metric-label { color: var(--text-secondary); }
                    .metric-val { font-weight: 600; color: #f1f5f9; }
                    .tag-green { color: #34d399; }
                    .tag-blue { color: #60a5fa; }
                    .notice-box {
                        background: rgba(15, 23, 42, 0.6);
                        border: 1px solid rgba(59, 130, 246, 0.3);
                        border-radius: 12px;
                        padding: 18px;
                        margin-top: 14px;
                        font-size: 13px;
                        color: var(--text-secondary);
                        line-height: 1.6;
                    }
                    .notice-box strong { color: #f8fafc; }
                    .footer {
                        text-align: center;
                        font-size: 12px;
                        color: #64748b;
                        margin-top: 8px;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header-card">
                        <div class="header-accent"></div>
                        <div class="header-top">
                            <div class="brand">
                                <div class="brand-icon">
                                    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#3b82f6" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                                        <rect x="2" y="3" width="20" height="14" rx="2" ry="2"/>
                                        <line x1="8" y1="21" x2="16" y2="21"/>
                                        <line x1="12" y1="17" x2="12" y2="21"/>
                                    </svg>
                                </div>
                                <div>
                                    <h1 class="brand-title">Enterprise Workstation Remote Gateway</h1>
                                    <p class="brand-subtitle">Encrypted Multi-Branch Workstation Bridge</p>
                                </div>
                            </div>
                            <div class="status-badge">
                                <span class="status-dot"></span>
                                {{STATUS}}
                            </div>
                        </div>
                    </div>

                    <div class="info-card">
                        <div class="card-kicker">Gateway Transport &amp; Security</div>
                        <div class="metric-row">
                            <span class="metric-label">Gateway Status</span>
                            <span class="metric-val tag-green">{{GATEWAY_HEALTH}}</span>
                        </div>
                        <div class="metric-row">
                            <span class="metric-label">Transport Protocol</span>
                            <span class="metric-val tag-blue">Cloudflare TLS 1.3 Encrypted Tunnel</span>
                        </div>
                        <div class="metric-row">
                            <span class="metric-label">Session Security</span>
                            <span class="metric-val">AES-256 JWT Authentication</span>
                        </div>
                        <div class="metric-row">
                            <span class="metric-label">Network Exposure</span>
                            <span class="metric-val tag-green">Zero Inbound Port-Forwarding</span>
                        </div>
                        <div class="metric-row">
                            <span class="metric-label">Gateway Timestamp</span>
                            <span class="metric-val">{{TIME}}</span>
                        </div>

                        <div class="notice-box">
                            <strong>🔒 Notice for Workstation Users:</strong><br>
                            Direct web browser navigation to this endpoint is restricted. This encrypted URL is a dedicated service gateway for authorized branch desktop computers. To connect, open your authorized <strong>Jasvi Desktop Workstation</strong> application and configure this endpoint address under <em>Settings &gt; Server Gateway</em>.
                        </div>
                    </div>

                    <div class="footer">
                        Enterprise Workstation Remote Gateway &bull; Protected by End-to-End Encryption
                    </div>
                </div>
            </body>
            </html>
            """
            .replace("{{STATUS}}", dbOk ? "ACTIVE &bull; ENCRYPTED" : "OFFLINE")
            .replace("{{GATEWAY_HEALTH}}", dbOk ? "Ready for Workstation Connections" : "Connecting")
            .replace("{{TIME}}", nowFormatted);

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }
}
