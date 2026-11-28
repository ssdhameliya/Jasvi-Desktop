package org.example.server.enquiry;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Responsive Mobile Browser Quick-View Portal served directly by Spring Boot.
 * Requires valid HMAC-SHA256 signature sent in ADMIN/SUPER_ADMIN WhatsApp & Email alerts.
 */
@RestController
@RequestMapping({"/public/enquiry-view", "/api/public/enquiry-view"})
public class PublicEnquiryViewController {
    private final EnquiryService service;

    public PublicEnquiryViewController(EnquiryService service) {
        this.service = service;
    }

    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> renderCard(
            @RequestParam(name = "id", required = false) Long id,
            @RequestParam(name = "token", required = false) String token) {
        if (id == null || token == null || token.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(renderErrorHtml("Missing security parameters."));
        }
        Optional<EnquiryDtos.EnquiryDetailResponse> found = service.loadForMobileView(id, token);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(renderErrorHtml("Invalid or unauthorized enquiry link."));
        }
        return ResponseEntity.ok(renderEnquiryHtml(found.get(), token, false));
    }

    @PostMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> applyQuickAction(
            @RequestParam(name = "id") long id,
            @RequestParam(name = "token") String token,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "note", required = false) String note) {
        Optional<EnquiryDtos.EnquiryDetailResponse> updated = service.updateFromMobileView(id, token, status, note);
        if (updated.isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(renderErrorHtml("Invalid or unauthorized enquiry link."));
        }
        return ResponseEntity.ok(renderEnquiryHtml(updated.get(), token, true));
    }

    private static String renderEnquiryHtml(EnquiryDtos.EnquiryDetailResponse detail, String token, boolean savedBanner) {
        EnquiryDtos.EnquiryRow e = detail.enquiry();
        String banner = savedBanner
                ? "<div class=\"banner\">Status &amp; note saved directly to Jasvi ERP. Your desktop team sees this update live.</div>"
                : "";
        String phoneDigits = e.phone().replaceAll("[^0-9]", "");
        String telHref = phoneDigits.isBlank() ? "#" : "tel:" + esc(e.phone());
        String waHref = phoneDigits.isBlank() ? "#"
                : "https://wa.me/" + (phoneDigits.length() == 10 ? "91" + phoneDigits : phoneDigits)
                  + "?text=" + URLEncoder.encode("Hello " + e.fullName() + ", regarding your DS Industries enquiry " + e.enquiryNo() + ":", StandardCharsets.UTF_8);
        String mailHref = e.email().isBlank() ? "#"
                : "mailto:" + esc(e.email()) + "?subject=" + URLEncoder.encode("DS Industries • Re: " + e.enquiryNo(), StandardCharsets.UTF_8);

        StringBuilder activityHtml = new StringBuilder();
        for (var a : detail.activity()) {
            activityHtml.append("<div class=\"act\"><strong>").append(esc(a.actionType()))
                    .append("</strong> &bull; <span>").append(esc(a.actorUsername())).append("</span><div class=\"muted\">")
                    .append(esc(a.detail())).append(" &bull; ").append(esc(a.createdAt())).append("</div></div>");
        }

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>%s &bull; Jasvi ERP Mobile Enquiry</title>
              <style>
                * { box-sizing: border-box; }
                body { margin: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #f1f5f9; color: #0f172a; padding: 14px; }
                .wrap { max-width: 560px; margin: 0 auto; background: #ffffff; border-radius: 16px; border: 1px solid #e2e8f0; overflow: hidden; box-shadow: 0 10px 25px rgba(15,23,42,0.08); }
                .hdr { background: linear-gradient(135deg, #0f766e, #0e7490); color: #fff; padding: 18px 20px; }
                .hdr .tag { font-size: 11px; text-transform: uppercase; letter-spacing: 0.08em; opacity: 0.85; font-weight: 700; }
                .hdr h1 { margin: 4px 0 6px; font-size: 20px; }
                .pill { display: inline-block; padding: 4px 10px; border-radius: 999px; font-size: 11px; font-weight: 700; background: rgba(255,255,255,0.2); color: #fff; }
                .sec { padding: 16px 20px; border-top: 1px solid #f1f5f9; }
                .sec h2 { margin: 0 0 10px; font-size: 12px; text-transform: uppercase; letter-spacing: 0.06em; color: #0f766e; }
                .grid { display: grid; grid-template-columns: 110px 1fr; row-gap: 8px; column-gap: 10px; font-size: 14px; }
                .lbl { color: #64748b; font-weight: 600; }
                .val { color: #0f172a; font-weight: 500; word-break: break-word; }
                .msg { background: #f8fafc; border-left: 4px solid #0f766e; padding: 12px; border-radius: 8px; font-size: 14px; line-height: 1.55; white-space: pre-wrap; }
                .btns { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
                .btn { display: block; text-align: center; text-decoration: none; padding: 11px 8px; border-radius: 10px; font-weight: 600; font-size: 13px; border: 1px solid #cbd5e1; color: #0f172a; background: #fff; }
                .btn-wa { background: #16a34a; border-color: #16a34a; color: #fff; }
                .btn-call { background: #0284c7; border-color: #0284c7; color: #fff; }
                .btn-mail { background: #7c3aed; border-color: #7c3aed; color: #fff; }
                .btn-dis { opacity: 0.4; pointer-events: none; }
                select, textarea { width: 100%%; padding: 10px 12px; border-radius: 10px; border: 1px solid #cbd5e1; font-size: 14px; font-family: inherit; margin-bottom: 10px; }
                .save-btn { width: 100%%; padding: 12px; border-radius: 10px; border: none; background: #0f766e; color: #fff; font-weight: 700; font-size: 14px; cursor: pointer; }
                .banner { background: #ecfdf5; border-bottom: 1px solid #a7f3d0; color: #065f46; padding: 12px 20px; font-size: 13px; font-weight: 600; }
                .act { padding: 8px 0; border-bottom: 1px dashed #e2e8f0; font-size: 13px; }
                .muted { color: #64748b; font-size: 12px; margin-top: 2px; }
              </style>
            </head>
            <body>
              <div class="wrap">
                <div class="hdr">
                  <div class="tag">%s &bull; %s</div>
                  <h1>%s</h1>
                  <span class="pill">Status: %s</span>
                  <span class="pill">%s</span>
                </div>
                %s
                <div class="sec">
                  <h2>Client Details</h2>
                  <div class="grid">
                    <div class="lbl">Client</div><div class="val">%s</div>
                    %s%s%s%s
                  </div>
                </div>
                <div class="sec">
                  <h2>Product &amp; Requirement</h2>
                  <div class="grid">
                    %s%s%s%s%s%s
                  </div>
                  <div class="msg" style="margin-top:10px;">%s</div>
                </div>
                <div class="sec">
                  <h2>Quick Contact</h2>
                  <div class="btns">
                    <a class="btn btn-call %s" href="%s">Call</a>
                    <a class="btn btn-wa %s" href="%s" target="_blank" rel="noopener">WhatsApp</a>
                    <a class="btn btn-mail %s" href="%s">Email</a>
                  </div>
                </div>
                <div class="sec">
                  <h2>Admin Update (Syncs to Desktop)</h2>
                  <form method="POST">
                    <input type="hidden" name="id" value="%d">
                    <input type="hidden" name="token" value="%s">
                    <select name="status">
                      %s%s%s%s%s%s
                    </select>
                    <textarea name="note" rows="3" placeholder="Add internal note or next step for Desktop team..."></textarea>
                    <button type="submit" class="save-btn">Save Update to ERP</button>
                  </form>
                </div>
                <div class="sec">
                  <h2>Activity Log</h2>
                  %s
                </div>
              </div>
            </body>
            </html>
            """.formatted(
                esc(e.enquiryNo()),
                esc(e.enquiryType()), esc(e.sourcePage()),
                esc(e.enquiryNo()),
                esc(e.status()),
                esc(e.createdAt()),
                banner,
                esc(e.fullName()),
                optRow("Company", e.companyName()),
                optRow("Phone", e.phone()),
                optRow("Email", e.email()),
                optRow("Location", e.cityState()),
                optRow("Product", e.productSeries()),
                optRow("Size (NPS)", e.npsSize()),
                optRow("Class", e.pressureClass()),
                optRow("Material", e.bodyMaterial()),
                optRow("Quantity", e.quantityText()),
                e.rating() != null ? optRow("Rating", e.rating() + " / 5") : "",
                e.message().isBlank() ? "(No detailed message)" : esc(e.message()),
                phoneDigits.isBlank() ? "btn-dis" : "", telHref,
                phoneDigits.isBlank() ? "btn-dis" : "", waHref,
                e.email().isBlank() ? "btn-dis" : "", mailHref,
                e.id(), esc(token),
                statusOption("NEW", e.status()),
                statusOption("IN_REVIEW", e.status()),
                statusOption("QUOTED", e.status()),
                statusOption("WON", e.status()),
                statusOption("LOST", e.status()),
                statusOption("SPAM", e.status()),
                activityHtml.toString());
    }

    private static String optRow(String label, String value) {
        if (value == null || value.isBlank()) return "";
        return "<div class=\"lbl\">" + esc(label) + "</div><div class=\"val\">" + esc(value) + "</div>";
    }

    private static String statusOption(String opt, String current) {
        String sel = opt.equalsIgnoreCase(current) ? " selected" : "";
        return "<option value=\"" + opt + "\"" + sel + ">" + opt + "</option>";
    }

    private static String renderErrorHtml(String msg) {
        return """
            <!DOCTYPE html><html lang="en"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1.0">
            <title>Access Notice &bull; Jasvi ERP</title></head>
            <body style="font-family:sans-serif;background:#f8fafc;padding:32px 16px;text-align:center;">
              <div style="max-width:440px;margin:0 auto;background:#fff;padding:24px;border-radius:14px;border:1px solid #e2e8f0;">
                <h2 style="color:#dc2626;margin:0 0 8px;">Enquiry Link Notice</h2>
                <p style="color:#475569;font-size:14px;">%s</p>
              </div>
            </body></html>
            """.formatted(esc(msg));
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
