package org.example.server.licensing;

import org.example.server.auth.SmtpMailService;
import org.example.server.communication.EmailOutboxService;
import org.example.server.communication.ServerNotificationTemplateService;
import org.example.server.persistence.JpaNativeRepository;
import org.example.server.util.BusinessClock;
import org.example.server.whatsapp.BusinessWhatsappService;
import org.example.server.whatsapp.WahaDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Automated Subscription Expiry, Concurrent Seat Utilization, and Dormant Workstation Watchdog.
 * Dispatches stage-deduplicated reminders (30 / 15 / 7 / 1 days, grace period, expired) via
 * Email Outbox + WhatsApp + Notification Center alerts without spamming clients.
 */
@Service
public class SubscriptionLifecycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionLifecycleScheduler.class);

    public record WatchdogSummary(int expiryAlertsSent, int seatWarningsRaised, int offlineAlertsRaised) {}

    private final JpaNativeRepository jdbc;
    private final ClientRegistryService registryService;
    private final ObjectProvider<EmailOutboxService> outboxProvider;
    private final ObjectProvider<SmtpMailService> mailProvider;
    private final ObjectProvider<BusinessWhatsappService> whatsappProvider;

    public SubscriptionLifecycleScheduler(
            JpaNativeRepository jdbc,
            ClientRegistryService registryService,
            ObjectProvider<EmailOutboxService> outboxProvider,
            ObjectProvider<SmtpMailService> mailProvider,
            ObjectProvider<BusinessWhatsappService> whatsappProvider) {
        this.jdbc = jdbc;
        this.registryService = registryService;
        this.outboxProvider = outboxProvider;
        this.mailProvider = mailProvider;
        this.whatsappProvider = whatsappProvider;
    }

    @Scheduled(cron = "${dse.licensing.watchdog-cron:0 30 9 * * *}")
    public void scheduledWatchdog() {
        runWatchdog(false);
    }

    public WatchdogSummary runWatchdog(boolean forceAlerts) {
        if (!forceAlerts && "false".equalsIgnoreCase(setting("licensing.expiry.reminder.enabled", "true"))) {
            return new WatchdogSummary(0, 0, 0);
        }
        List<Integer> checkpoints = parseCheckpoints(setting("licensing.expiry.reminder.days", "30,15,7,1"));
        List<Map<String, Object>> clients;
        try {
            clients = jdbc.queryForList("""
                    SELECT id, company_name, machine_id, contact_person, phone, email, tier, max_users,
                           annual_fee, valid_until, status, active_users_count, last_heartbeat_at,
                           last_expiry_alert_stage, last_offline_alert_at, seat_alert_sent_at
                    FROM licensed_client_registry
                    WHERE UPPER(COALESCE(status, 'ACTIVE')) = 'ACTIVE'
                    ORDER BY id ASC
                    """);
        } catch (Exception ex) {
            log.debug("Subscription watchdog skipped: {}", ex.getMessage());
            return new WatchdogSummary(0, 0, 0);
        }

        LocalDate today = LocalDate.now(BusinessClock.zone());
        var templates = ServerNotificationTemplateService.forJdbc(jdbc);
        Map<String, Object> comm = registryService.getCommercialSettings();
        String supportEmail = Objects.toString(comm.getOrDefault("supportEmail", templates.companySupportEmail()), "");
        String supportPhone = Objects.toString(comm.getOrDefault("supportPhone", ""), "");
        String issuerBrand = Objects.toString(comm.getOrDefault("issuerBrand", templates.companyName() + " ERP"), "");
        int offlineHours = intSetting("licensing.workstation.offlineAlertHours", 48);

        int expiryCount = 0;
        int seatCount = 0;
        int offlineCount = 0;

        for (Map<String, Object> client : clients) {
            Long id = client.get("id") instanceof Number n ? n.longValue() : null;
            String company = Objects.toString(client.get("company_name"), "Client");
            String machineId = Objects.toString(client.get("machine_id"), "");
            String tier = Objects.toString(client.get("tier"), "GOLD");
            String email = Objects.toString(client.get("email"), "").trim();
            String phone = Objects.toString(client.get("phone"), "").replaceAll("[^0-9]", "");
            double fee = client.get("annual_fee") instanceof Number fn ? fn.doubleValue() : 0.0;
            Instant validUntil = LicenseVerifierService.toInstant(client.get("valid_until"));

            if (id != null && client.get("valid_until") != null) {
                LocalDate expiryDate = validUntil.atZone(BusinessClock.zone()).toLocalDate();
                long daysRemaining = ChronoUnit.DAYS.between(today, expiryDate);
                String stage = resolveStage(daysRemaining, checkpoints);
                String prevStage = Objects.toString(client.get("last_expiry_alert_stage"), "");
                if (stage != null && (forceAlerts || !stage.equals(prevStage))) {
                    dispatchExpiryWarning(id, company, tier, machineId, expiryDate, daysRemaining, stage, fee, email, phone, supportEmail, supportPhone, issuerBrand, templates);
                    expiryCount++;
                }
            }

            int maxUsers = client.get("max_users") instanceof Number mn ? mn.intValue() : 0;
            int activeUsers = client.get("active_users_count") instanceof Number an ? an.intValue() : 0;
            if (id != null && maxUsers > 0 && activeUsers >= Math.max(1, (int) Math.ceil(maxUsers * 0.90))) {
                if (isOlderThanHours(client.get("seat_alert_sent_at"), 24)) {
                    raiseSeatAlert(id, company, machineId, activeUsers, maxUsers);
                    seatCount++;
                }
            }

            if (id != null && client.get("last_heartbeat_at") != null && isOlderThanHours(client.get("last_heartbeat_at"), offlineHours)) {
                if (isOlderThanHours(client.get("last_offline_alert_at"), 72)) {
                    raiseOfflineAlert(id, company, machineId, offlineHours);
                    offlineCount++;
                }
            }
        }
        return new WatchdogSummary(expiryCount, seatCount, offlineCount);
    }

    private void dispatchExpiryWarning(Long id, String company, String tier, String machineId, LocalDate expiryDate,
                                       long daysRemaining, String stage, double fee, String email, String phone,
                                       String supportEmail, String supportPhone, String issuerBrand,
                                       ServerNotificationTemplateService templates) {
        String feeText = NumberFormat.getCurrencyInstance(Locale.of("en", "IN")).format(fee) + " + GST";
        Map<String, String> tokens = Map.of(
                "CompanyName", company,
                "Tier", tier,
                "MachineId", machineId,
                "ValidUntil", expiryDate.toString(),
                "DaysRemaining", Long.toString(daysRemaining),
                "ReminderStage", humanStageLabel(stage, daysRemaining),
                "RenewalFee", feeText,
                "SupportEmail", supportEmail,
                "SupportPhone", supportPhone,
                "IssuerBrand", issuerBrand
        );
        String recipientEmails = mergeEmailTargets(email, supportEmail);
        if (!recipientEmails.isBlank()) {
            try {
                String urgencyHex = daysRemaining <= 7 ? "#b91c1c" : "#d97706";
                String urgencyEndHex = daysRemaining <= 7 ? "#991b1b" : "#b45309";
                String kpiHtml = org.example.shared.communication.TokenTemplateEngine.kpiGridHtml(
                        "Subscription Renewal Status",
                        List.of(
                                new org.example.shared.communication.TokenTemplateEngine.KpiTile("Days Remaining", daysRemaining + " Days", humanStageLabel(stage, daysRemaining), urgencyHex),
                                new org.example.shared.communication.TokenTemplateEngine.KpiTile("Licensed Tier", tier, "Active Edition", "#0369a1"),
                                new org.example.shared.communication.TokenTemplateEngine.KpiTile("Renewal Fee", feeText, "Annual / Term", "#0f172a")
                        ),
                        3
                );
                String metaRows = org.example.shared.communication.TokenTemplateEngine.keyValueSectionHtml(
                        "Workspace & License Details",
                        urgencyHex,
                        List.of(
                                new org.example.shared.communication.TokenTemplateEngine.KeyValueRow("Company", company, false),
                                new org.example.shared.communication.TokenTemplateEngine.KeyValueRow("Machine ID", machineId, true),
                                new org.example.shared.communication.TokenTemplateEngine.KeyValueRow("Valid Until", expiryDate.toString(), false, urgencyHex, daysRemaining <= 7 ? "#fee2e2" : "#fef3c7"),
                                new org.example.shared.communication.TokenTemplateEngine.KeyValueRow("Support Desk", supportEmail + (supportPhone.isBlank() ? "" : " | " + supportPhone), false)
                        )
                );
                var rendered = templates.renderRichEmail(
                        "subscription_expiry_warning",
                        tokens,
                        new ServerNotificationTemplateService.RichCardStyle(
                                urgencyHex,
                                urgencyEndHex,
                                "LICENSE LIFECYCLE NOTICE",
                                daysRemaining <= 7 ? "URGENT EXPIRY" : "RENEWAL DUE",
                                urgencyHex,
                                issuerBrand + " • " + humanStageLabel(stage, daysRemaining),
                                company + " (" + tier + " Tier)",
                                "Subscription expiry notice for " + company + " (" + daysRemaining + " day(s) remaining).",
                                List.of(
                                        new org.example.shared.communication.TokenTemplateEngine.MetaColumn("COMPANY", company),
                                        new org.example.shared.communication.TokenTemplateEngine.MetaColumn("EXPIRY DATE", expiryDate.toString(), urgencyHex),
                                        new org.example.shared.communication.TokenTemplateEngine.MetaColumn("DAYS LEFT", String.valueOf(daysRemaining), urgencyHex)
                                ),
                                List.of(kpiHtml, metaRows),
                                List.of(),
                                issuerBrand + (supportEmail.isBlank() ? "" : " • Support: " + supportEmail),
                                null
                        )
                );
                EmailOutboxService outbox = outboxProvider != null ? outboxProvider.getIfAvailable() : null;
                if (outbox != null) {
                    outbox.sendWithOutbox(new EmailOutboxService.OutboxSendRequest(
                            recipientEmails, null, null, rendered.subject(), rendered.body(),
                            List.of(), null, null, null, "LICENSE", id, machineId, "subscription_expiry_warning"));
                } else if (mailProvider != null && mailProvider.getIfAvailable() != null && mailProvider.getIfAvailable().isConfigured()) {
                    mailProvider.getIfAvailable().sendBusiness(recipientEmails, rendered.subject(), rendered.body());
                }
            } catch (Exception ex) {
                log.debug("Expiry warning email failed for {}: {}", company, ex.getMessage());
            }
        }
        if (!phone.isBlank() && whatsappProvider != null && whatsappProvider.getIfAvailable() != null) {
            try {
                String waPhone = phone.length() == 10 ? "91" + phone : phone;
                String waMsg = templates.renderWhatsapp("subscription_expiry_warning", tokens);
                whatsappProvider.getIfAvailable().sendText(new WahaDtos.TextSendRequest(waPhone, waMsg, null));
            } catch (Exception ex) {
                log.debug("Expiry warning WhatsApp failed for {}: {}", company, ex.getMessage());
            }
        }
        try {
            jdbc.update("""
                    UPDATE licensed_client_registry
                    SET last_expiry_alert_stage = ?, last_expiry_alert_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, stage, id);
            jdbc.update("""
                    INSERT INTO notifications(title, message, severity, category, is_read, target_fxml, reference_no, module_key, record_id, action_code, created_at)
                    VALUES (?, ?, ?, 'licensing', 0, '/fxml/pages/ClientRegistry.fxml', ?, 'LICENSE', ?, 'EXPIRY_REMINDER', ?)
                    """,
                    "Subscription " + humanStageLabel(stage, daysRemaining),
                    company + " (" + tier + ") valid until " + expiryDate + " (" + daysRemaining + " day(s) remaining).",
                    daysRemaining <= 7 ? "WARNING" : "INFO",
                    machineId, id, BusinessClock.nowUtcText());
        } catch (Exception ignored) {}
    }

    private void raiseSeatAlert(Long id, String company, String machineId, int activeUsers, int maxUsers) {
        try {
            jdbc.update("UPDATE licensed_client_registry SET seat_alert_sent_at = CURRENT_TIMESTAMP WHERE id = ?", id);
            jdbc.update("""
                    INSERT INTO notifications(title, message, severity, category, is_read, target_fxml, reference_no, module_key, record_id, action_code, created_at)
                    VALUES (?, ?, 'WARNING', 'licensing', 0, '/fxml/pages/ClientRegistry.fxml', ?, 'LICENSE', ?, 'SEAT_CAPACITY', ?)
                    """,
                    "High Seat Utilization (" + activeUsers + "/" + maxUsers + ")",
                    company + " (" + machineId + ") has reached " + activeUsers + " of " + maxUsers + " licensed concurrent workstation seats.",
                    machineId, id, BusinessClock.nowUtcText());
        } catch (Exception ignored) {}
    }

    private void raiseOfflineAlert(Long id, String company, String machineId, int thresholdHours) {
        try {
            jdbc.update("UPDATE licensed_client_registry SET last_offline_alert_at = CURRENT_TIMESTAMP WHERE id = ?", id);
            jdbc.update("""
                    INSERT INTO notifications(title, message, severity, category, is_read, target_fxml, reference_no, module_key, record_id, action_code, created_at)
                    VALUES (?, ?, 'INFO', 'licensing', 0, '/fxml/pages/ClientRegistry.fxml', ?, 'LICENSE', ?, 'WORKSTATION_STALE', ?)
                    """,
                    "Workstation Heartbeat Overdue",
                    company + " (" + machineId + ") has not reported a workstation heartbeat in over " + thresholdHours + " hours.",
                    machineId, id, BusinessClock.nowUtcText());
        } catch (Exception ignored) {}
    }

    private static String resolveStage(long daysRemaining, List<Integer> checkpoints) {
        if (daysRemaining < -14) return "EXPIRED";
        if (daysRemaining < 0) return "GRACE_PERIOD";
        int maxMatch = -1;
        for (int cp : checkpoints) {
            if (daysRemaining <= cp && (maxMatch < 0 || cp < maxMatch)) {
                maxMatch = cp;
            }
        }
        return maxMatch >= 0 ? (maxMatch + "_DAYS") : null;
    }

    private static String humanStageLabel(String stage, long daysRemaining) {
        return switch (stage) {
            case "EXPIRED" -> "Subscription Expired";
            case "GRACE_PERIOD" -> "Grace Period Active";
            default -> Math.max(0, daysRemaining) + " Day(s) Remaining";
        };
    }

    private static List<Integer> parseCheckpoints(String raw) {
        List<Integer> out = new ArrayList<>();
        if (raw != null) {
            for (String p : raw.split("[,;]")) {
                try {
                    int v = Integer.parseInt(p.trim());
                    if (v >= 0) out.add(v);
                } catch (Exception ignored) {}
            }
        }
        return out.isEmpty() ? List.of(30, 15, 7, 1) : out;
    }

    private static boolean isOlderThanHours(Object timestampObj, int hours) {
        if (timestampObj == null) return true;
        Instant ts = LicenseVerifierService.toInstant(timestampObj);
        return ts.isBefore(Instant.now().minus(Math.max(1, hours), ChronoUnit.HOURS));
    }

    private static String mergeEmailTargets(String a, String b) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (a != null && !a.isBlank()) set.add(a.trim());
        if (b != null && !b.isBlank()) set.add(b.trim());
        return String.join(", ", set);
    }

    private String setting(String key, String def) {
        try {
            List<String> rows = jdbc.query("SELECT setting_value FROM application_setting WHERE setting_key = ?", (rs, i) -> rs.getString(1), key);
            if (!rows.isEmpty() && rows.getFirst() != null && !rows.getFirst().isBlank()) return rows.getFirst().trim();
        } catch (Exception ignored) {}
        return def;
    }

    private int intSetting(String key, int def) {
        try {
            return Integer.parseInt(setting(key, Integer.toString(def)));
        } catch (Exception ignored) {
            return def;
        }
    }
}
