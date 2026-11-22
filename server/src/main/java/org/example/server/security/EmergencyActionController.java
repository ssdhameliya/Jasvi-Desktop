package org.example.server.security;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Controller for emergency one-click action links.
 * Complies with strict data boundary rules by delegating all execution to EmergencyActionService.
 */
@Controller
public class EmergencyActionController {

    private final EmergencyActionService emergencyActionService;

    public EmergencyActionController(EmergencyActionService emergencyActionService) {
        this.emergencyActionService = emergencyActionService;
    }

    @GetMapping(value = "/api/security/emergency/action", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> executeEmergencyAction(@RequestParam("token") String rawToken) {
        EmergencyActionService.ExecutionResult result = emergencyActionService.executeEmergencyAction(rawToken);
        String html = renderResultPage(result);
        return result.success() ? ResponseEntity.ok(html) : ResponseEntity.badRequest().body(html);
    }

    @GetMapping(value = "/api/security/emergency/status", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> getIncidentStatus(@RequestParam("ticket") String ticketId) {
        EmergencyActionService.IncidentStatus status = emergencyActionService.getIncidentStatus(ticketId);
        String html = renderStatusPage(status);
        return status.found() ? ResponseEntity.ok(html) : ResponseEntity.status(404).body(html);
    }

    private String renderStatusPage(EmergencyActionService.IncidentStatus status) {
        String company = status.companyName() != null && !status.companyName().isBlank() ? status.companyName() : emergencyActionService.companyName();
        String app = status.appName() != null && !status.appName().isBlank() ? status.appName() : emergencyActionService.appName();

        if (!status.found()) {
            return """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta charset="utf-8">
                        <title>Incident Not Found - {{APP_NAME}}</title>
                        <style>
                            body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #f1f5f9; margin: 0; padding: 40px 16px; display: flex; align-items: center; justify-content: center; min-height: 80vh; }
                            .card { max-width: 520px; width: 100%; background: #ffffff; border-radius: 16px; box-shadow: 0 10px 25px rgba(0,0,0,0.08); padding: 36px 32px; text-align: center; border-top: 6px solid #dc2626; }
                            h1 { font-size: 20px; color: #0f172a; margin: 16px 0 8px 0; }
                            p { font-size: 14px; color: #64748b; line-height: 1.6; }
                            .footer { margin-top: 24px; font-size: 12px; color: #94a3b8; }
                        </style>
                    </head>
                    <body>
                        <div class="card">
                            <div style="font-size: 48px;">⚠️</div>
                            <h1>Security Incident Not Found</h1>
                            <p>No active emergency incident matching ticket <strong>#{{TICKET_ID}}</strong> was found in the database.</p>
                            <div class="footer">{{COMPANY_NAME}} ERP • Autonomous Incident Response Authority</div>
                        </div>
                    </body>
                    </html>
                    """
                    .replace("{{TICKET_ID}}", status.ticketId() == null ? "UNKNOWN" : status.ticketId())
                    .replace("{{APP_NAME}}", app)
                    .replace("{{COMPANY_NAME}}", company);
        }

        boolean isLocked = status.locked();
        boolean isForcePw = status.forcePasswordChange();
        String themeColor = isLocked ? "#d97706" : "#16a34a";
        String icon = isLocked ? "⏳" : (isForcePw ? "🔑" : "✅");
        String title = isLocked ? "Incident In Progress: Awaiting Super Admin" : (isForcePw ? "Temporary Password Dispatched" : "Administrator Account Unlocked");
        String badge = isLocked ? "STATUS: LOCKED • AWAITING ACTION" : (isForcePw ? "STATUS: PASSWORD RESET • FIRST-LOGIN REQUIRED" : "STATUS: ACTIVE & UNLOCKED");
        String message = isLocked
                ? "An emergency security alert has been dispatched to Super Admin. As soon as Super Admin unlocks or resets your account, this page will update automatically."
                : (isForcePw
                ? "Super Admin has assigned a secure temporary password to your account. Please check your email, sign in on Desktop, and set your permanent password."
                : "Super Admin has successfully unlocked your account. You can now sign in immediately on your Desktop workstation using your existing password.");
        String autoRefreshMeta = isLocked ? "<meta http-equiv=\"refresh\" content=\"10\">" : "";

        return """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    {{AUTO_REFRESH}}
                    <title>{{TITLE}} - {{APP_NAME}} Security</title>
                    <style>
                        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #f1f5f9; margin: 0; padding: 40px 16px; display: flex; align-items: center; justify-content: center; min-height: 80vh; }
                        .card { max-width: 540px; width: 100%; background: #ffffff; border-radius: 16px; box-shadow: 0 10px 25px rgba(0,0,0,0.08); padding: 36px 32px; text-align: center; border-top: 6px solid {{THEME_COLOR}}; }
                        .icon { font-size: 48px; margin-bottom: 12px; }
                        .badge { display: inline-block; background: #f8fafc; border: 1px solid #cbd5e1; border-radius: 20px; padding: 5px 14px; font-size: 12.5px; font-weight: 700; color: {{THEME_COLOR}}; margin-bottom: 16px; letter-spacing: 0.5px; }
                        h1 { font-size: 22px; color: #0f172a; margin: 0 0 12px 0; font-weight: 700; }
                        p { font-size: 14.5px; color: #475569; line-height: 1.6; margin: 0 0 20px 0; }
                        .meta-box { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 10px; padding: 14px; text-align: left; margin: 18px 0; }
                        .meta-row { display: flex; justify-content: space-between; font-size: 13px; padding: 4px 0; border-bottom: 1px solid #f1f5f9; }
                        .meta-row:last-child { border-bottom: none; }
                        .meta-key { color: #64748b; font-weight: 600; }
                        .meta-val { color: #0f172a; font-weight: 600; }
                        .pulse-hint { font-size: 12px; color: #94a3b8; font-style: italic; margin-top: 10px; }
                        .footer { margin-top: 24px; font-size: 12px; color: #94a3b8; }
                    </style>
                </head>
                <body>
                    <div class="card">
                        <div class="icon">{{ICON}}</div>
                        <div class="badge">{{BADGE}}</div>
                        <h1>{{TITLE}}</h1>
                        <p>{{MESSAGE}}</p>

                        <div class="meta-box">
                            <div class="meta-row"><span class="meta-key">Incident Ticket</span><span class="meta-val">#{{TICKET_ID}}</span></div>
                            <div class="meta-row"><span class="meta-key">Administrator</span><span class="meta-val">{{ADMIN_NAME}} ({{USERNAME}})</span></div>
                            <div class="meta-row"><span class="meta-key">Last Checked</span><span class="meta-val">{{TIMESTAMP}}</span></div>
                        </div>

                        {{PULSE_HINT}}

                        <div class="footer">{{COMPANY_NAME}} ERP • Autonomous Incident Response Authority</div>
                    </div>
                </body>
                </html>
                """
                .replace("{{AUTO_REFRESH}}", autoRefreshMeta)
                .replace("{{THEME_COLOR}}", themeColor)
                .replace("{{ICON}}", icon)
                .replace("{{BADGE}}", badge)
                .replace("{{TITLE}}", title)
                .replace("{{MESSAGE}}", message)
                .replace("{{TICKET_ID}}", status.ticketId())
                .replace("{{ADMIN_NAME}}", status.adminName())
                .replace("{{USERNAME}}", status.username())
                .replace("{{TIMESTAMP}}", status.timestamp())
                .replace("{{APP_NAME}}", app)
                .replace("{{COMPANY_NAME}}", company)
                .replace("{{PULSE_HINT}}", isLocked ? "<div class=\"pulse-hint\">⏳ Live monitor active: Page auto-refreshes every 10 seconds</div>" : "");
    }

    private String renderResultPage(EmergencyActionService.ExecutionResult result) {
        String company = emergencyActionService.companyName();
        String app = emergencyActionService.appName();
        String themeColor = result.success() ? "#16a34a" : "#dc2626";
        String icon = result.success() ? "✅" : "⚠️";
        return """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>{{TITLE}} - {{APP_NAME}} Security</title>
                    <style>
                        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #f1f5f9; margin: 0; padding: 40px 16px; display: flex; align-items: center; justify-content: center; min-height: 80vh; }
                        .card { max-width: 520px; width: 100%; background: #ffffff; border-radius: 16px; box-shadow: 0 10px 25px rgba(0,0,0,0.08); padding: 36px 32px; text-align: center; border-top: 6px solid {{THEME_COLOR}}; }
                        .icon { font-size: 48px; margin-bottom: 16px; }
                        h1 { font-size: 22px; color: #0f172a; margin: 0 0 12px 0; font-weight: 700; }
                        p { font-size: 15px; color: #475569; line-height: 1.6; margin: 0 0 20px 0; }
                        .ticket-pill { display: inline-block; background: #f8fafc; border: 1px solid #cbd5e1; border-radius: 20px; padding: 4px 14px; font-size: 13px; font-weight: 600; color: #334155; margin-bottom: 20px; }
                        .subtext { background: #f0fdf4; border: 1px solid #bbf7d0; color: #166534; padding: 12px; border-radius: 8px; font-size: 13.5px; font-weight: 600; margin-top: 10px; }
                        .footer { margin-top: 28px; font-size: 12px; color: #94a3b8; }
                    </style>
                </head>
                <body>
                    <div class="card">
                        <div class="icon">{{ICON}}</div>
                        {{TICKET_PILL}}
                        <h1>{{TITLE}}</h1>
                        <p>{{MESSAGE}}</p>
                        {{SUBTEXT}}
                        <div class="footer">{{COMPANY_NAME}} ERP • Autonomous Incident Response Authority</div>
                    </div>
                </body>
                </html>
                """
                .replace("{{THEME_COLOR}}", themeColor)
                .replace("{{ICON}}", icon)
                .replace("{{TICKET_PILL}}", result.ticketId() != null ? "<div class=\"ticket-pill\">Incident Ticket #" + result.ticketId() + "</div>" : "")
                .replace("{{TITLE}}", result.title())
                .replace("{{MESSAGE}}", result.message())
                .replace("{{SUBTEXT}}", result.subtext() != null ? "<div class=\"subtext\">" + result.subtext() + "</div>" : "")
                .replace("{{APP_NAME}}", app)
                .replace("{{COMPANY_NAME}}", company);
    }
}
