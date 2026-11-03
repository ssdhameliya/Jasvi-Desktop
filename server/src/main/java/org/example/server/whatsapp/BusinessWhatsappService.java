package org.example.server.whatsapp;

import org.example.server.audit.AuditService;
import org.example.server.documents.CanonicalDocumentService;
import org.example.server.persistence.JpaNativeRepository;
import org.example.server.persistence.entity.WhatsappSenderChannelEntity;
import org.example.server.persistence.repository.WhatsappSenderChannelRepository;
import org.example.server.security.CurrentUser;
import org.example.server.support.SupportDtos;
import org.example.server.support.SupportService;
import org.example.server.util.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class BusinessWhatsappService {
    private static final Logger log = LoggerFactory.getLogger(BusinessWhatsappService.class);

    private final WahaApiClient wahaClient;
    private final WhatsappSenderChannelRepository channelRepo;
    private final JpaNativeRepository jdbc;
    private final CanonicalDocumentService canonicalDocs;
    private final SupportService supportService;
    private final AuditService audit;

    public BusinessWhatsappService(
            WahaApiClient wahaClient,
            WhatsappSenderChannelRepository channelRepo,
            JpaNativeRepository jdbc,
            CanonicalDocumentService canonicalDocs,
            SupportService supportService,
            AuditService audit) {
        this.wahaClient = wahaClient;
        this.channelRepo = channelRepo;
        this.jdbc = jdbc;
        this.canonicalDocs = canonicalDocs;
        this.supportService = supportService;
        this.audit = audit;
    }

    private String getSetting(String key, String def) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT setting_value FROM application_setting WHERE setting_key=?", key);
            if (!rows.isEmpty() && rows.getFirst().get("setting_value") != null) {
                return Objects.toString(rows.getFirst().get("setting_value"), def);
            }
        } catch (Exception ignored) {}
        return def;
    }

    private void updateSetting(String key, String value) {
        jdbc.update(
                "INSERT INTO application_setting (setting_key, setting_value, updated_at) " +
                        "VALUES (?, ?, CURRENT_TIMESTAMP) " +
                        "ON CONFLICT (setting_key) DO UPDATE SET setting_value=EXCLUDED.setting_value, updated_at=CURRENT_TIMESTAMP",
                key, value == null ? "" : value);
    }

    @Transactional(readOnly = true)
    public WahaDtos.WahaSettingsDto getSettings() {
        String endpoint = getSetting("whatsapp.waha.endpoint", "http://localhost:3000");
        String apiKey = getSetting("whatsapp.waha.api_key", "");
        boolean enabled = "true".equalsIgnoreCase(getSetting("whatsapp.enabled", "true"));
        Long defaultChannelId = null;
        try {
            String ch = getSetting("whatsapp.default_channel_id", "");
            if (!ch.isBlank()) defaultChannelId = Long.parseLong(ch.trim());
        } catch (Exception ignored) {}

        if (defaultChannelId == null) {
            defaultChannelId = channelRepo.findFirstByIsDefaultTrueAndIsActiveTrue()
                    .map(WhatsappSenderChannelEntity::getId)
                    .orElse(null);
        }

        return new WahaDtos.WahaSettingsDto(endpoint, apiKey, enabled, defaultChannelId);
    }

    @Transactional
    public void saveSettings(WahaDtos.WahaSettingsDto dto) {
        if (dto == null) return;
        updateSetting("whatsapp.waha.endpoint", dto.endpoint());
        updateSetting("whatsapp.waha.api_key", dto.apiKey());
        updateSetting("whatsapp.enabled", String.valueOf(dto.enabled()));

        if (dto.defaultChannelId() != null) {
            updateSetting("whatsapp.default_channel_id", String.valueOf(dto.defaultChannelId()));
            setDefaultChannel(dto.defaultChannelId());
        }

        audit.logChange("SETTING", 0, "WHATSAPP_CONFIG_UPDATED",
                "WhatsApp server configuration updated", "Endpoint", "", dto.endpoint());
    }

    @Transactional
    public List<WahaDtos.ChannelDto> listChannels() {
        try {
            // Purge dummy placeholder channels with blank phone number
            List<WhatsappSenderChannelEntity> all = channelRepo.findAll();
            for (var c : all) {
                if (c.getPhoneNumber() == null || c.getPhoneNumber().isBlank() || "PRIMARY_WHATSAPP".equalsIgnoreCase(c.getChannelCode())) {
                    channelRepo.delete(c);
                }
            }

            List<Map<String, Object>> lookupSenders = jdbc.queryForList(
                    "SELECT lookup_value, description FROM lookup_master " +
                    "WHERE (UPPER(lookup_type) = 'WHATSAPP_SENDER' OR UPPER(lookup_type) = 'WHATSAPP SENDERS' OR UPPER(lookup_type) = 'WHATSAPP_SENDERS') AND is_active = 1");
            for (Map<String, Object> r : lookupSenders) {
                if (r.get("lookup_value") != null) {
                    String phone = r.get("lookup_value").toString().trim();
                    String desc = r.get("description") != null ? r.get("description").toString().trim() : "WhatsApp Sender";
                    if (!phone.isBlank()) {
                        var existing = channelRepo.findFirstByPhoneNumberAndIsActiveTrue(phone);
                        if (existing.isEmpty()) {
                            WhatsappSenderChannelEntity newCh = new WhatsappSenderChannelEntity();
                            String digits = phone.replaceAll("[^0-9]", "");
                            newCh.setChannelCode("WHA_" + digits);
                            newCh.setDisplayName(desc.isBlank() ? ("WhatsApp " + phone) : desc);
                            newCh.setPhoneNumber(phone);
                            newCh.setSessionName("session_" + (digits.isBlank() ? "default" : digits));
                            newCh.setIsActive(true);
                            newCh.setIsDefault(channelRepo.count() == 0);
                            channelRepo.save(newCh);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        List<WhatsappSenderChannelEntity> entities = channelRepo.findAllByIsActiveTrueOrderByIsDefaultDescDisplayNameAsc().stream()
                .filter(c -> c.getPhoneNumber() != null && !c.getPhoneNumber().isBlank())
                .toList();

        // Check live session statuses if WAHA is configured
        WahaDtos.WahaSettingsDto settings = getSettings();
        Map<String, String> sessionStatuses = new HashMap<>();
        try {
            List<WahaDtos.SessionStatus> liveSessions = wahaClient.getSessions(settings.endpoint(), settings.apiKey());
            for (WahaDtos.SessionStatus s : liveSessions) {
                sessionStatuses.put(s.name().toLowerCase(Locale.ROOT), s.status());
            }
        } catch (Exception ignored) {}

        List<WahaDtos.ChannelDto> dtos = new ArrayList<>(entities.size());
        for (WhatsappSenderChannelEntity ch : entities) {
            if (ch.getSessionName() == null || "default".equalsIgnoreCase(ch.getSessionName())) {
                String d = ch.getPhoneNumber() != null ? ch.getPhoneNumber().replaceAll("[^0-9]", "") : "";
                if (!d.isBlank()) {
                    ch.setSessionName("session_" + d);
                    channelRepo.save(ch);
                }
            }
            String liveStatus = sessionStatuses.get(ch.getSessionName().toLowerCase(Locale.ROOT));
            if (liveStatus != null && !liveStatus.equalsIgnoreCase(ch.getConnectionStatus())) {
                ch.setConnectionStatus(liveStatus.toUpperCase(Locale.ROOT));
                channelRepo.save(ch);
            }
            dtos.add(toChannelDto(ch));
        }
        return dtos;
    }

    @Transactional
    public WahaDtos.ChannelDto saveChannel(WahaDtos.ChannelSaveRequest req) {
        if (req == null) throw new IllegalArgumentException("Channel request cannot be null");
        if (req.displayName() == null || req.displayName().isBlank()) {
            throw new IllegalArgumentException("Display name is required");
        }

        WhatsappSenderChannelEntity entity;
        if (req.id() != null && req.id() > 0) {
            entity = channelRepo.findById(req.id())
                    .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + req.id()));
        } else {
            entity = new WhatsappSenderChannelEntity();
            String code = req.channelCode();
            if (code == null || code.isBlank()) {
                code = "WA_" + System.currentTimeMillis();
            }
            entity.setChannelCode(code.trim().toUpperCase(Locale.ROOT));
        }

        entity.setDisplayName(req.displayName().trim());
        entity.setPhoneNumber(req.phoneNumber() == null ? "" : req.phoneNumber().trim());
        entity.setSessionName((req.sessionName() == null || req.sessionName().isBlank()) ? "default" : req.sessionName().trim());
        if (req.isActive() != null) entity.setIsActive(req.isActive());

        if (Boolean.TRUE.equals(req.isDefault())) {
            // Unset other defaults
            jdbc.update("UPDATE whatsapp_sender_channel SET is_default = false WHERE id <> ?",
                    entity.getId() == null ? -1L : entity.getId());
            entity.setIsDefault(true);
        } else if (entity.getId() == null) {
            // If first channel, make it default
            long count = channelRepo.count();
            if (count == 0) entity.setIsDefault(true);
        }

        entity = channelRepo.save(entity);
        if (entity.getIsDefault()) {
            updateSetting("whatsapp.default_channel_id", String.valueOf(entity.getId()));
        }

        return toChannelDto(entity);
    }

    @Transactional
    public void deleteChannel(Long id) {
        if (id == null) return;
        WhatsappSenderChannelEntity entity = channelRepo.findById(id).orElse(null);
        if (entity != null) {
            entity.setIsActive(false);
            entity.setIsDefault(false);
            channelRepo.save(entity);
        }
    }

    @Transactional
    public void setDefaultChannel(Long id) {
        if (id == null) return;
        jdbc.update("UPDATE whatsapp_sender_channel SET is_default = false");
        jdbc.update("UPDATE whatsapp_sender_channel SET is_default = true WHERE id = ?", id);
        updateSetting("whatsapp.default_channel_id", String.valueOf(id));
    }

    public WahaDtos.HealthResponse testConnection() {
        WahaDtos.WahaSettingsDto settings = getSettings();
        return wahaClient.ping(settings.endpoint(), settings.apiKey());
    }

    public WahaDtos.HealthResponse startDockerEngine() {
        try {
            log.info("Attempting to start WAHA Docker container on server...");
            ProcessBuilder pb = new ProcessBuilder("docker", "start", "waha");
            Process p = pb.start();
            boolean finished = p.waitFor(6, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished || p.exitValue() != 0) {
                String osArch = System.getProperty("os.arch", "").toLowerCase();
                String img = (osArch.contains("arm") || osArch.contains("aarch64")) ? "devlikeapro/waha:arm" : "devlikeapro/waha";
                ProcessBuilder runPb = new ProcessBuilder(
                        "docker", "run", "-d", "--name", "waha", "-p", "3000:3000", "--restart", "always",
                        "-e", "WAHA_NO_API_KEY=true",
                        "-e", "WAHA_DASHBOARD_NO_PASSWORD=true",
                        "-e", "WHATSAPP_SWAGGER_NO_PASSWORD=true",
                        img);
                Process runP = runPb.start();
                runP.waitFor(12, java.util.concurrent.TimeUnit.SECONDS);
            }
            Thread.sleep(2500);
        } catch (Exception e) {
            log.warn("Docker auto-start attempt finished with: {}", e.getMessage());
        }
        return testConnection();
    }

    public WahaDtos.QrResponse getQrCode(String sessionName) {
        WahaDtos.WahaSettingsDto settings = getSettings();
        String session = (sessionName == null || sessionName.isBlank()) ? "default" : sessionName.trim();
        return wahaClient.getQrCode(settings.endpoint(), settings.apiKey(), session);
    }

    public WahaDtos.SendResponse sendDocument(WahaDtos.DocumentSendRequest req) {
        if (req == null) throw new IllegalArgumentException("Request cannot be null");
        WahaDtos.WahaSettingsDto settings = getSettings();
        if (!settings.enabled()) {
            throw new IllegalStateException("WhatsApp integration is currently disabled in Settings.");
        }

        WhatsappSenderChannelEntity channel = resolveChannel(req.channelId());
        String chatId = formatChatId(req.recipientPhone());

        String companyName = getSetting("company.name", "Jasvi Industries");
        String canonicalType = mapDocumentType(req.documentType());

        WahaDtos.SendResponse response;
        try {
            if (canonicalType != null && req.documentNumber() != null && !req.documentNumber().isBlank()) {
                // Render document PDF
                var rendered = canonicalDocs.render(canonicalType, req.documentNumber().trim(), "pdf");
                String filename = rendered.fileName() != null && !rendered.fileName().isBlank()
                        ? rendered.fileName()
                        : (req.documentNumber().trim() + ".pdf");

                String caption = (req.customMessage() != null && !req.customMessage().isBlank())
                        ? req.customMessage()
                        : ("Dear Customer,\n\nPlease find attached your " + canonicalType.replace('_', ' ') +
                        " #" + req.documentNumber().trim() + " from " + companyName + ".\n\nThank you for your business!");

                response = wahaClient.sendFile(settings.endpoint(), settings.apiKey(),
                        channel.getSessionName(), chatId, filename, "application/pdf", rendered.bytes(), caption);
                if (response.success() && "true".equalsIgnoreCase(getSetting("email.signature.attachToWhatsapp", "false"))) {
                    try {
                        String sigPath = getSetting("email.signature.image", "");
                        if (!sigPath.isBlank() && java.nio.file.Files.isRegularFile(java.nio.file.Path.of(sigPath))) {
                            byte[] sigBytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(sigPath));
                            wahaClient.sendFile(settings.endpoint(), settings.apiKey(), channel.getSessionName(), chatId, "Signature.png", "image/png", sigBytes, "Authorized Signature");
                        }
                    } catch (Exception ignored) {}
                }
            } else {
                // Plain text message dispatch
                String text = (req.customMessage() != null && !req.customMessage().isBlank())
                        ? req.customMessage()
                        : ("Notification from " + companyName);
                response = wahaClient.sendText(settings.endpoint(), settings.apiKey(),
                        channel.getSessionName(), chatId, text);
            }
        } catch (Exception e) {
            log.error("Failed to render or dispatch WhatsApp document: {}", e.getMessage(), e);
            response = new WahaDtos.SendResponse(false, "Failed to send: " + e.getMessage(), "", channel.getChannelCode(), req.recipientPhone());
        }

        // Post-dispatch status handling and communication logging
        try {
            String status = response.success() ? "SENT" : "FAILED";
            String error = response.success() ? null : response.message();
            String entityType = req.documentType() != null ? req.documentType().toUpperCase(Locale.ROOT) : "COMMUNICATION";
            int entityId = req.documentId() != null ? req.documentId() : 0;
            String username = "SYSTEM";
            try { username = CurrentUser.require().username(); } catch (Exception ignored) {}

            jdbc.update("INSERT INTO communication_log (entity_type, entity_id, channel, recipient, subject, status, error_message, created_by, created_at) " +
                            "VALUES (?, ?, 'WHATSAPP', ?, ?, ?, ?, ?, ?)",
                    entityType, entityId, req.recipientPhone(),
                    "WhatsApp: " + (req.documentNumber() != null ? req.documentNumber() : "Direct Message"),
                    status, error, username, BusinessClock.nowUtcText());

            if (response.success() && req.documentId() != null && req.documentId() > 0) {
                if ("SALE".equalsIgnoreCase(entityType) || "SALES_INVOICE".equalsIgnoreCase(entityType)) {
                    jdbc.update("UPDATE sales_header SET whatsapp_sent = 1 WHERE id = ?", req.documentId());
                } else if ("QUOTATION".equalsIgnoreCase(entityType)) {
                    jdbc.update("UPDATE quotation_header SET whatsapp_sent = 1 WHERE id = ?", req.documentId());
                }
            }
        } catch (Exception logEx) {
            log.warn("Failed to write communication audit log for WhatsApp: {}", logEx.getMessage());
        }

        return response;
    }

    public WahaDtos.SendResponse sendText(WahaDtos.TextSendRequest req) {
        if (req == null) throw new IllegalArgumentException("Request cannot be null");
        WahaDtos.WahaSettingsDto settings = getSettings();
        if (!settings.enabled()) {
            throw new IllegalStateException("WhatsApp integration is currently disabled in Settings.");
        }

        WhatsappSenderChannelEntity channel = resolveChannel(req.channelId());
        String chatId = formatChatId(req.recipientPhone());

        WahaDtos.SendResponse response = wahaClient.sendText(settings.endpoint(), settings.apiKey(),
                channel.getSessionName(), chatId, req.message());

        try {
            String status = response.success() ? "SENT" : "FAILED";
            String error = response.success() ? null : response.message();
            String username = "SYSTEM";
            try { username = CurrentUser.require().username(); } catch (Exception ignored) {}

            jdbc.update("INSERT INTO communication_log (entity_type, entity_id, channel, recipient, subject, status, error_message, created_by, created_at) " +
                            "VALUES ('WHATSAPP_MESSAGE', 0, 'WHATSAPP', ?, 'Direct WhatsApp Message', ?, ?, ?, ?)",
                    req.recipientPhone(), status, error, username, BusinessClock.nowUtcText());
        } catch (Exception logEx) {
            log.warn("Failed to record text communication log: {}", logEx.getMessage());
        }

        return response;
    }

    private WhatsappSenderChannelEntity resolveChannel(Long channelId) {
        if (channelId != null && channelId > 0) {
            return channelRepo.findById(channelId)
                    .filter(WhatsappSenderChannelEntity::getIsActive)
                    .orElseGet(this::resolveDefaultChannel);
        }
        return resolveDefaultChannel();
    }

    private WhatsappSenderChannelEntity resolveDefaultChannel() {
        String activeSender = getSetting("whatsapp.active_sender_number", "");
        String targetPhone = "";
        if (!activeSender.isBlank()) {
            String trimmed = activeSender.trim();
            try {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT lookup_value FROM lookup_master WHERE UPPER(lookup_type) = 'WHATSAPP_SENDER' AND is_active = 1 AND (UPPER(lookup_code) = UPPER(?) OR BTRIM(lookup_value) = ?)",
                        trimmed, trimmed);
                if (!rows.isEmpty() && rows.getFirst().get("lookup_value") != null) {
                    targetPhone = Objects.toString(rows.getFirst().get("lookup_value"), "").trim();
                }
            } catch (Exception ignored) {}
            if (targetPhone.isBlank()) {
                targetPhone = trimmed;
            }

            String digits = targetPhone.replaceAll("[^0-9]", "");
            var matching = channelRepo.findFirstByPhoneNumberAndIsActiveTrue(targetPhone);
            if (matching.isPresent()) return matching.get();

            if (!digits.isBlank()) {
                for (var ch : channelRepo.findAllByIsActiveTrueOrderByIsDefaultDescDisplayNameAsc()) {
                    String chDigits = ch.getPhoneNumber() == null ? "" : ch.getPhoneNumber().replaceAll("[^0-9]", "");
                    if (!chDigits.isBlank() && (chDigits.equals(digits) || chDigits.endsWith(digits) || digits.endsWith(chDigits))) {
                        return ch;
                    }
                }
            }

            var byCode = channelRepo.findByChannelCode(trimmed);
            if (byCode.isPresent() && byCode.get().getIsActive()) return byCode.get();
        }

        var defChannel = channelRepo.findFirstByIsDefaultTrueAndIsActiveTrue();
        if (defChannel.isPresent()) {
            var ch = defChannel.get();
            if (!targetPhone.isBlank() && (ch.getPhoneNumber() == null || ch.getPhoneNumber().isBlank())) {
                ch.setPhoneNumber(targetPhone);
                channelRepo.save(ch);
            }
            return ch;
        }

        final String fallbackPhone = targetPhone;
        return channelRepo.findAllByIsActiveTrueOrderByIsDefaultDescDisplayNameAsc().stream()
                .findFirst()
                .orElseGet(() -> {
                    WhatsappSenderChannelEntity fallback = new WhatsappSenderChannelEntity();
                    fallback.setChannelCode("DEFAULT");
                    fallback.setDisplayName("Default WhatsApp");
                    fallback.setSessionName("default");
                    fallback.setPhoneNumber(fallbackPhone);
                    fallback.setIsDefault(true);
                    fallback.setIsActive(true);
                    return channelRepo.save(fallback);
                });
    }

    public static String formatChatId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Recipient phone number is required");
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        if (digits.length() == 10) {
            digits = "91" + digits;
        }
        if (digits.length() < 10) {
            throw new IllegalArgumentException("Invalid phone number format: " + raw);
        }
        return digits.endsWith("@c.us") ? digits : digits + "@c.us";
    }

    private String mapDocumentType(String type) {
        if (type == null || type.isBlank()) return null;
        String upper = type.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "SALE", "SALES", "SALES_INVOICE", "INVOICE" -> "SALES_INVOICE";
            case "PURCHASE", "PURCHASE_INVOICE" -> "PURCHASE_INVOICE";
            case "QUOTE", "QUOTATION" -> "QUOTATION";
            case "SALES_RETURN", "CREDIT_NOTE" -> "SALES_RETURN";
            case "PURCHASE_RETURN", "DEBIT_NOTE" -> "PURCHASE_RETURN";
            case "PAYMENT", "PAYMENT_RECEIPT" -> "PAYMENT_RECEIPT";
            case "DELIVERY_CHALLAN" -> "DELIVERY_CHALLAN";
            default -> upper;
        };
    }

    private WahaDtos.ChannelDto toChannelDto(WhatsappSenderChannelEntity e) {
        return new WahaDtos.ChannelDto(
                e.getId(),
                e.getChannelCode(),
                e.getDisplayName(),
                e.getPhoneNumber(),
                e.getSessionName(),
                Boolean.TRUE.equals(e.getIsDefault()),
                Boolean.TRUE.equals(e.getIsActive()),
                e.getConnectionStatus(),
                e.getCreatedAt() != null ? e.getCreatedAt().toString() : "",
                e.getUpdatedAt() != null ? e.getUpdatedAt().toString() : ""
        );
    }
}
