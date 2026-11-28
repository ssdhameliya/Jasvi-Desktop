package org.example.api.authority;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.api.ApiSession;
import org.example.config.ConfigManager;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.Base64;

public final class BusinessEmailClient {
    private final ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private String base() { return ConfigManager.getDataApiBaseUrl().replaceAll("/+$", "") + "/api/authority/email"; }

    public void send(String recipient, String subject, String body, Path attachment) {
        send(recipient, subject, body, attachment, null);
    }

    public void send(String recipient, String subject, String body, Path attachment, Path signatureImage) {
        send(recipient, subject, body, attachment, signatureImage, null, null);
    }

    public void send(String recipient, String subject, String body, Path attachment, Path signatureImage, String senderEmail, String senderDisplayName) {
        send(recipient, null, null, subject, body, attachment, signatureImage, senderEmail, senderDisplayName, null, null, null, null);
    }

    public void send(String recipient, String recipientCc, String recipientBcc, String subject, String body,
                     Path attachment, Path signatureImage, String senderEmail, String senderDisplayName,
                     String entityType, Long entityId, String referenceNo, String templateKey) {
        try {
            String name = attachment == null ? null : attachment.getFileName().toString();
            String data = attachment == null ? null : Base64.getEncoder().encodeToString(Files.readAllBytes(attachment));
            String sigName = signatureImage == null ? null : signatureImage.getFileName().toString();
            String sigData = signatureImage == null ? null : Base64.getEncoder().encodeToString(Files.readAllBytes(signatureImage));
            request("POST", base(), new Request(recipient, subject, body, name, data, sigName, sigData,
                    senderEmail, senderDisplayName, recipientCc, recipientBcc, entityType, entityId, referenceNo, templateKey), Result.class);
        } catch (Exception e) {
            throw e instanceof RuntimeException x ? x : new IllegalStateException(e);
        }
    }

    public void resend(String recipient, String subject, String body, Path attachment) {
        resend(recipient, subject, body, attachment, null);
    }

    public void resend(String recipient, String subject, String body, Path attachment, Path signatureImage) {
        resend(recipient, subject, body, attachment, signatureImage, null, null);
    }

    public void resend(String recipient, String subject, String body, Path attachment, Path signatureImage, String senderEmail, String senderDisplayName) {
        resend(recipient, null, null, subject, body, attachment, signatureImage, senderEmail, senderDisplayName, null, null, null, null);
    }

    public void resend(String recipient, String recipientCc, String recipientBcc, String subject, String body,
                       Path attachment, Path signatureImage, String senderEmail, String senderDisplayName,
                       String entityType, Long entityId, String referenceNo, String templateKey) {
        try {
            String name = attachment == null ? null : attachment.getFileName().toString();
            String data = attachment == null ? null : Base64.getEncoder().encodeToString(Files.readAllBytes(attachment));
            String sigName = signatureImage == null ? null : signatureImage.getFileName().toString();
            String sigData = signatureImage == null ? null : Base64.getEncoder().encodeToString(Files.readAllBytes(signatureImage));
            request("POST", base() + "/resend", new Request(recipient, subject, body, name, data, sigName, sigData,
                    senderEmail, senderDisplayName, recipientCc, recipientBcc, entityType, entityId, referenceNo, templateKey), Result.class);
        } catch (Exception e) {
            throw e instanceof RuntimeException x ? x : new IllegalStateException(e);
        }
    }

    public OutboxPage outbox(String status, String q, int page, int size) {
        String st = URLEncoder.encode(status == null ? "ALL" : status, StandardCharsets.UTF_8);
        String query = URLEncoder.encode(q == null ? "" : q, StandardCharsets.UTF_8);
        return request("GET", base() + "/outbox?status=" + st + "&q=" + query + "&page=" + Math.max(0, page) + "&size=" + Math.max(10, size), null, OutboxPage.class);
    }

    public RetryResult retryOutbox(long id) {
        return request("POST", base() + "/outbox/" + id + "/retry", null, RetryResult.class);
    }

    public Settings settings() { return request("GET", base() + "/settings", null, Settings.class); }
    public Settings saveSettings(Settings settings) { return request("PUT", base() + "/settings", settings, Settings.class); }
    public Result test(String recipient) { return request("POST", base() + "/test", new TestRequest(recipient), Result.class); }

    private <T> T request(String method, String uri, Object body, Class<T> type) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(90)).header("Accept", "application/json");
            ApiSession.authorize(builder);
            if ("GET".equals(method)) builder.GET();
            else builder.header("Content-Type", "application/json").method(method,
                    body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) throw new ApiSession.AuthenticationRequiredException("Please sign in again");
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IllegalStateException(serverMessage(response.statusCode(), response.body()));
            return json.readValue(response.body(), type);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw e instanceof RuntimeException x ? x : new IllegalStateException(e);
        }
    }

    private String serverMessage(int status, String body) {
        try {
            var node=json.readTree(body==null?"":body);
            String message=node.path("message").asText("").trim();
            if(!message.isBlank()) return message;
        } catch (Exception ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
        return "Company-server email request failed (HTTP " + status + ").";
    }

    private record Request(String recipient, String subject, String body, String attachmentName, String attachmentBase64,
                           String signatureImageName, String signatureImageBase64, String senderEmail, String senderDisplayName,
                           String recipientCc, String recipientBcc, String entityType, Long entityId, String referenceNo, String templateKey) {
        public Request(String recipient, String subject, String body, String attachmentName, String attachmentBase64, String signatureImageName, String signatureImageBase64) {
            this(recipient, subject, body, attachmentName, attachmentBase64, signatureImageName, signatureImageBase64, null, null, null, null, null, null, null, null);
        }
    }
    private record TestRequest(String recipient) {}
    public record Settings(String email, String appPassword, String host, Integer port, boolean passwordConfigured) {}
    public record Result(boolean success, String message) {}
    public record OutboxItemDto(long id, String channel, String recipientTo, String recipientCc, String recipientBcc,
                                String subject, String status, int attemptCount, int maxAttempts, String nextRetryAt,
                                String lastError, String entityType, Long entityId, String referenceNo, String templateKey,
                                String createdBy, String createdAt, String sentAt) {}
    public record OutboxSummary(long total, long sent, long retrying, long pending, long failed) {}
    public record OutboxPage(java.util.List<OutboxItemDto> rows, OutboxSummary summary, int page, int size, long totalRows, int totalPages) {}
    public record RetryResult(boolean success, String status, String message) {}
}
