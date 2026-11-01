package org.example.api.whatsapp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.api.ApiRuntime;
import org.example.api.ApiSession;
import org.example.config.ConfigManager;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Desktop API Client for Spring Boot Business WhatsApp (WAHA Docker) Endpoints.
 * Calls /api/authority/whatsapp/*
 */
public final class BusinessWhatsappApiClient {

    private final HttpClient http = ApiRuntime.HTTP;
    private final ObjectMapper json = ApiRuntime.JSON;

    public BusinessWhatsappApiClient() {}

    private static String base() {
        String b = ConfigManager.getDataApiBaseUrl();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    public WahaSettingsDto getSettings() {
        return get("/api/authority/whatsapp/settings", WahaSettingsDto.class);
    }

    public WahaSettingsDto saveSettings(WahaSettingsDto dto) {
        return put("/api/authority/whatsapp/settings", dto, WahaSettingsDto.class);
    }

    public HealthResponse testConnection() {
        return post("/api/authority/whatsapp/test", null, HealthResponse.class);
    }

    public List<ChannelDto> listChannels() {
        return get("/api/authority/whatsapp/channels", new TypeReference<List<ChannelDto>>() {});
    }

    public ChannelDto saveChannel(ChannelSaveRequest req) {
        return post("/api/authority/whatsapp/channels", req, ChannelDto.class);
    }

    public Map<String, Object> deleteChannel(Long id) {
        return req("DELETE", "/api/authority/whatsapp/channels/" + id, null, new TypeReference<Map<String, Object>>() {});
    }

    public Map<String, Object> setDefaultChannel(Long id) {
        return put("/api/authority/whatsapp/channels/" + id + "/default", null, new TypeReference<Map<String, Object>>() {});
    }

    public QrResponse getQrCode(String session) {
        String path = (session == null || session.isBlank())
                ? "/api/authority/whatsapp/qr"
                : "/api/authority/whatsapp/qr/" + session;
        return get(path, QrResponse.class);
    }

    public SendResponse sendDocument(DocumentSendRequest req) {
        return post("/api/authority/whatsapp/send-document", req, SendResponse.class);
    }

    public SendResponse sendText(TextSendRequest req) {
        return post("/api/authority/whatsapp/send-text", req, SendResponse.class);
    }

    private <T> T get(String p, Class<T> c) {
        return req("GET", p, null, c, null);
    }

    private <T> T get(String p, TypeReference<T> t) {
        return req("GET", p, null, null, t);
    }

    private <T> T post(String p, Object b, Class<T> c) {
        return req("POST", p, b, c, null);
    }

    private <T> T put(String p, Object b, Class<T> c) {
        return req("PUT", p, b, c, null);
    }

    private <T> T put(String p, Object b, TypeReference<T> t) {
        return req("PUT", p, b, null, t);
    }

    private <T> T req(String m, String p, Object body, TypeReference<T> t) {
        return req(m, p, body, null, t);
    }

    private <T> T req(String m, String p, Object body, Class<T> c, TypeReference<T> t) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + p))
                    .timeout(Duration.ofSeconds(35))
                    .header("Accept", "application/json");
            ApiSession.authorize(b);
            if (body != null) {
                b.header("Content-Type", "application/json")
                 .method(m, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
            } else {
                b.method(m, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() < 200 || r.statusCode() >= 300) {
                throw new IllegalStateException(ApiRuntime.errorMessage("WhatsApp API", r.statusCode(), r.body()));
            }
            return t != null ? json.readValue(r.body(), t) : json.readValue(r.body(), c);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot reach WhatsApp API at " + base(), e);
        }
    }

    // DTO records matching server WahaDtos
    public record WahaSettingsDto(String endpoint, String apiKey, boolean enabled, Long defaultChannelId) {}
    public record ChannelDto(Long id, String channelCode, String displayName, String phoneNumber, String sessionName, boolean isDefault, boolean isActive, String connectionStatus, String createdAt, String updatedAt) {}
    public record ChannelSaveRequest(Long id, String channelCode, String displayName, String phoneNumber, String sessionName, Boolean isDefault, Boolean isActive) {}
    public record DocumentSendRequest(String documentType, String documentNumber, Integer documentId, Long channelId, String recipientPhone, String customMessage, Long rowVersion) {}
    public record TextSendRequest(String recipientPhone, String message, Long channelId) {}
    public record SendResponse(boolean success, String message, String messageId, String channelCode, String recipientPhone) {}
    public record QrResponse(String session, String qrImageBase64, String status, String message) {}
    public record HealthResponse(boolean connected, String message, String serverVersion, List<SessionStatus> sessions) {}
    public record SessionStatus(String name, String status) {}
}
