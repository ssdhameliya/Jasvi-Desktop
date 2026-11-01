package org.example.server.system;

import org.example.server.auth.SmtpMailService;
import org.example.server.persistence.JpaNativeRepository;
import org.example.server.whatsapp.BusinessWhatsappService;
import org.example.server.whatsapp.WahaDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CloudStorageHealthGuardian {
    private static final Logger log = LoggerFactory.getLogger(CloudStorageHealthGuardian.class);
    private static final Duration COOLDOWN_DURATION = Duration.ofHours(24);

    private final SmtpMailService mailService;
    private final BusinessWhatsappService whatsappService;
    private final JpaNativeRepository jdbc;

    @Value("${dse.deployment.environment:LOCAL}")
    private String deploymentEnv;

    @Value("${dse.server.address:127.0.0.1}")
    private String serverAddress;

    private final Map<String, Instant> lastAlertTimestamps = new ConcurrentHashMap<>();
    private String lastAlertLevel = "NONE";

    public CloudStorageHealthGuardian(
            SmtpMailService mailService,
            BusinessWhatsappService whatsappService,
            JpaNativeRepository jdbc) {
        this.mailService = mailService;
        this.whatsappService = whatsappService;
        this.jdbc = jdbc;
    }

    @Scheduled(fixedRate = 7200000, initialDelay = 120000)
    public void inspectStorageHealth() {
        try {
            File root = new File("/");
            long totalDisk = root.getTotalSpace();
            long freeDisk = root.getUsableSpace();
            if (totalDisk <= 0) {
                File[] roots = File.listRoots();
                if (roots != null && roots.length > 0) {
                    totalDisk = roots[0].getTotalSpace();
                    freeDisk = roots[0].getUsableSpace();
                }
            }
            if (totalDisk <= 0) return;

            long usedDisk = Math.max(0, totalDisk - freeDisk);
            double diskUsagePercent = Math.round(((double) usedDisk / (double) totalDisk) * 1000.0) / 10.0;

            String currentLevel = "NONE";
            if (diskUsagePercent >= 85.0) {
                currentLevel = "CRITICAL";
            } else if (diskUsagePercent >= 80.0) {
                currentLevel = "WARNING";
            }

            if ("NONE".equals(currentLevel)) {
                lastAlertLevel = "NONE";
                return;
            }

            // Anti-Spam Debounce Guard: 24h cooldown unless escalating to CRITICAL
            Instant now = Instant.now();
            Instant lastAlert = lastAlertTimestamps.get(currentLevel);
            boolean isEscalation = "CRITICAL".equals(currentLevel) && "WARNING".equals(lastAlertLevel);

            if (!isEscalation && lastAlert != null && Duration.between(lastAlert, now).compareTo(COOLDOWN_DURATION) < 0) {
                log.debug("Cloud storage alert ({}) throttled by 24-hour cooldown", currentLevel);
                return;
            }

            String env = deploymentEnv != null && !deploymentEnv.isBlank()
                    ? deploymentEnv.trim().toUpperCase(Locale.ROOT)
                    : "LOCAL";
            String serverIp = "PROD".equals(env) ? "130.210.35.1" : ("UAT".equals(env) ? "144.24.109.1" : serverAddress);

            long totalGb = totalDisk / (1024 * 1024 * 1024);
            long freeGb = freeDisk / (1024 * 1024 * 1024);
            long usedGb = usedDisk / (1024 * 1024 * 1024);

            String title = "[" + currentLevel + "] Cloud Storage Alert: " + env + " Server (" + serverIp + ")";
            String message = String.format(Locale.ROOT,
                    "⚠️ *%s*\n\n" +
                    "Storage on %s server (%s) has reached *%.1f%%* utilization.\n" +
                    "• Total Disk Space: %d GB\n" +
                    "• Used Disk Space: %d GB (%.1f%%)\n" +
                    "• Available Space: %d GB\n\n" +
                    "Action Required: Please review disk usage, clear old archives, or allocate additional volume space.\n" +
                    "Timestamp: %s",
                    title, env, serverIp, diskUsagePercent, totalGb, usedGb, diskUsagePercent, freeGb, now.toString());

            // 1. Dispatch Email Alert to Company Admin
            dispatchEmailAlert(title, message);

            // 2. Dispatch WhatsApp Alert to Primary Company Admin Mobile
            dispatchWhatsappAlert(message);

            lastAlertTimestamps.put(currentLevel, now);
            lastAlertLevel = currentLevel;
            log.warn("Cloud Storage Guardian dispatched {} alert (Disk: {}% used)", currentLevel, diskUsagePercent);

        } catch (Exception ex) {
            log.warn("Error during cloud storage health inspection: {}", ex.getMessage());
        }
    }

    private void dispatchEmailAlert(String subject, String body) {
        try {
            String adminEmail = null;
            try {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT email FROM users WHERE UPPER(COALESCE(role,'')) IN ('ADMIN','SUPER_ADMIN') AND email IS NOT NULL AND BTRIM(email) <> '' ORDER BY id ASC LIMIT 1");
                if (!rows.isEmpty() && rows.getFirst().get("email") != null) {
                    adminEmail = rows.getFirst().get("email").toString().trim();
                }
            } catch (Exception ignored) {}

            if (adminEmail == null || adminEmail.isBlank()) {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT setting_value FROM application_setting WHERE setting_key='company_email'");
                if (!rows.isEmpty() && rows.getFirst().get("setting_value") != null) {
                    adminEmail = rows.getFirst().get("setting_value").toString().trim();
                }
            }

            if (adminEmail != null && !adminEmail.isBlank() && mailService.isConfigured()) {
                mailService.sendBusiness(adminEmail, subject, body);
            }
        } catch (Exception e) {
            log.info("Storage Guardian email dispatch notice: {}", e.getMessage());
        }
    }

    private void dispatchWhatsappAlert(String message) {
        try {
            String adminPhone = null;
            try {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT phone FROM users WHERE UPPER(COALESCE(role,'')) IN ('ADMIN','SUPER_ADMIN') AND phone IS NOT NULL AND BTRIM(phone) <> '' ORDER BY id ASC LIMIT 1");
                if (!rows.isEmpty() && rows.getFirst().get("phone") != null) {
                    adminPhone = rows.getFirst().get("phone").toString().trim();
                }
            } catch (Exception ignored) {}

            if (adminPhone == null || adminPhone.isBlank()) {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT setting_value FROM application_setting WHERE setting_key='company_phone'");
                if (!rows.isEmpty() && rows.getFirst().get("setting_value") != null) {
                    adminPhone = rows.getFirst().get("setting_value").toString().trim();
                }
            }

            if (adminPhone != null && !adminPhone.isBlank()) {
                var channels = whatsappService.listChannels();
                if (!channels.isEmpty()) {
                    var defaultChannel = channels.stream().filter(WahaDtos.ChannelDto::isDefault).findFirst().orElse(channels.getFirst());
                    if ("CONNECTED".equalsIgnoreCase(defaultChannel.connectionStatus())) {
                        whatsappService.sendText(new WahaDtos.TextSendRequest(adminPhone, message, defaultChannel.id()));
                    }
                }
            }
        } catch (Exception e) {
            log.info("Storage Guardian WhatsApp dispatch notice: {}", e.getMessage());
        }
    }
}
