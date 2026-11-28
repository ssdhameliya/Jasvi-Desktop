package org.example.api.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.api.ApiRuntime;
import org.example.api.ApiSession;
import org.example.config.ConfigManager;

import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class TeamChatApiClient {
    private final HttpClient http = ApiRuntime.HTTP;
    private final ObjectMapper json = ApiRuntime.JSON;

    private static String baseUrl() {
        String b = ConfigManager.getDataApiBaseUrl();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    public DirectoryResponse directory() {
        return req("GET", "/api/chat/directory", null, DirectoryResponse.class, null);
    }

    public PresenceDto updatePresence(String status, String customNote) {
        return req("PUT", "/api/chat/presence", new PresenceUpdateRequest(status, customNote), PresenceDto.class, null);
    }

    public PresenceDto heartbeat() {
        return req("POST", "/api/chat/presence/heartbeat", null, PresenceDto.class, null);
    }

    public ChannelSummaryDto openDirect(int peerUserId) {
        return req("POST", "/api/chat/channels/direct", new OpenDirectRequest(peerUserId), ChannelSummaryDto.class, null);
    }

    public ChannelSummaryDto createGroup(String name, List<Integer> memberIds) {
        return req("POST", "/api/chat/channels/group", new CreateGroupRequest(name, memberIds), ChannelSummaryDto.class, null);
    }

    public List<MessageDto> listMessages(long channelId, Long beforeId, String query, int limit) {
        StringBuilder p = new StringBuilder("/api/chat/channels/").append(channelId).append("/messages?limit=").append(limit);
        if (beforeId != null && beforeId > 0) p.append("&beforeId=").append(beforeId);
        if (query != null && !query.isBlank()) p.append("&q=").append(enc(query.trim()));
        return req("GET", p.toString(), null, null, new TypeReference<List<MessageDto>>() {});
    }

    public MessageDto sendText(long channelId, String body, Long replyToId, String linkedDocType, String linkedDocNo) {
        return req("POST", "/api/chat/channels/" + channelId + "/messages",
                new SendMessageRequest(body, replyToId, linkedDocType, linkedDocNo), MessageDto.class, null);
    }

    public MessageDto sendWithAttachments(
            long channelId,
            String body,
            Long replyToId,
            String linkedDocType,
            String linkedDocNo,
            List<File> files
    ) {
        String boundary = "----JasviChatBoundary" + UUID.randomUUID().toString().replace("-", "");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (body != null && !body.isBlank()) writeFormField(out, boundary, "body", body);
            if (replyToId != null && replyToId > 0) writeFormField(out, boundary, "replyToId", String.valueOf(replyToId));
            if (linkedDocType != null && !linkedDocType.isBlank()) writeFormField(out, boundary, "linkedDocType", linkedDocType);
            if (linkedDocNo != null && !linkedDocNo.isBlank()) writeFormField(out, boundary, "linkedDocNo", linkedDocNo);

            if (files != null) {
                for (File f : files) {
                    if (f == null || !f.isFile()) continue;
                    String mime = Files.probeContentType(f.toPath());
                    if (mime == null || mime.isBlank()) mime = "application/octet-stream";
                    out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                    String safeFilename = f.getName().replace("\"", "_");
                    out.write(("Content-Disposition: form-data; name=\"files\"; filename=\"" + safeFilename + "\"\r\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.write(("Content-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    Files.copy(f.toPath(), out);
                    out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                }
            }
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/chat/channels/" + channelId + "/messages/upload"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Accept", "application/json")
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
            ApiSession.authorize(b);
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() / 100 != 2) {
                ApiRuntime.logHttpFailure("Team Chat attachment upload", r.statusCode(), r.body());
                throw new IllegalStateException(ApiRuntime.userMessage("uploading chat attachment", r.statusCode(), r.body()));
            }
            return json.readValue(r.body(), MessageDto.class);
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to upload chat attachment: " + e.getMessage(), e);
        }
    }

    public byte[] fetchAttachmentBytes(long attachmentId) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/chat/attachments/" + attachmentId))
                    .timeout(Duration.ofSeconds(60))
                    .GET();
            ApiSession.authorize(b);
            HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() / 100 != 2) {
                throw new IllegalStateException("Attachment download failed (HTTP " + r.statusCode() + ")");
            }
            return r.body();
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot download attachment: " + e.getMessage(), e);
        }
    }

    public void downloadAttachment(long attachmentId, Path destination) {
        byte[] bytes = fetchAttachmentBytes(attachmentId);
        try {
            if (destination.getParent() != null) Files.createDirectories(destination.getParent());
            Files.write(destination, bytes);
        } catch (IOException e) {
            throw new IllegalStateException("Could not save attachment to disk: " + e.getMessage(), e);
        }
    }

    public MessageDto editMessage(long messageId, String body) {
        return req("PUT", "/api/chat/messages/" + messageId, new EditMessageRequest(body), MessageDto.class, null);
    }

    public MessageDto deleteMessage(long messageId) {
        return req("DELETE", "/api/chat/messages/" + messageId, null, MessageDto.class, null);
    }

    public MessageDto react(long messageId, String emoji) {
        return req("POST", "/api/chat/messages/" + messageId + "/react", new ReactRequest(emoji), MessageDto.class, null);
    }

    public void markRead(long channelId, long lastReadMessageId) {
        req("POST", "/api/chat/channels/" + channelId + "/read", new MarkReadRequest(lastReadMessageId), Map.class, null);
    }

    public void notifyTyping(long channelId, boolean typing) {
        req("POST", "/api/chat/channels/" + channelId + "/typing", new TypingRequest(typing), Map.class, null);
    }

    public void consumeEventStream(Consumer<ChatStreamEvent> onEvent, BooleanSupplier keepRunning,
                                   Consumer<InputStream> onStreamOpened) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/chat/stream"))
                .timeout(Duration.ofMinutes(35))
                .header("Accept", "text/event-stream")
                .GET();
        ApiSession.authorize(b);
        HttpResponse<InputStream> response = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            try { response.body().close(); } catch (Exception ignored) {}
            throw new IllegalStateException("Chat SSE unauthorized (HTTP " + response.statusCode() + ")");
        }
        if (response.statusCode() / 100 != 2) {
            try { response.body().close(); } catch (Exception ignored) {}
            throw new IllegalStateException("Chat SSE stream refused (HTTP " + response.statusCode() + ")");
        }
        InputStream stream = response.body();
        if (onStreamOpened != null) onStreamOpened.accept(stream);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder dataBuf = new StringBuilder();
            String line;
            while (keepRunning.getAsBoolean() && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (!dataBuf.isEmpty()) {
                        String rawJson = dataBuf.toString().trim();
                        dataBuf.setLength(0);
                        if (rawJson.startsWith("{") && rawJson.contains("eventType")) {
                            ChatStreamEvent ev = json.readValue(rawJson, ChatStreamEvent.class);
                            if (ev != null && ev.eventType() != null) onEvent.accept(ev);
                        }
                    }
                } else if (line.startsWith("data:")) {
                    dataBuf.append(line.substring(5).trim()).append('\n');
                }
            }
        }
    }

    private static void writeFormField(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write("Content-Type: text/plain; charset=UTF-8\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private <T> T req(String method, String path, Object body, Class<T> c, TypeReference<T> t) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json");
            ApiSession.authorize(b);
            if (body != null) b.header("Content-Type", "application/json");
            String payload = body == null ? "" : json.writeValueAsString(body);
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() / 100 != 2) {
                ApiRuntime.logHttpFailure("Team Chat request", r.statusCode(), r.body());
                throw new IllegalStateException(ApiRuntime.userMessage("Team Chat", r.statusCode(), r.body()));
            }
            if (c != null) return json.readValue(r.body(), c);
            return json.readValue(r.body(), t);
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot reach Team Chat API: " + e.getMessage(), e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    public record PresenceDto(
            int userId, String username, String fullName, String role, String department,
            String branch, String status, String customNote, String lastHeartbeatUtc, String updatedUtc,
            String avatarData
    ) {
        public PresenceDto(int userId, String username, String fullName, String role, String department,
                           String branch, String status, String customNote, String lastHeartbeatUtc, String updatedUtc) {
            this(userId, username, fullName, role, department, branch, status, customNote, lastHeartbeatUtc, updatedUtc, "");
        }
    }

    public record DirectoryResponse(
            PresenceDto selfPresence, List<PresenceDto> users, List<ChannelSummaryDto> channels, long totalUnread
    ) {}

    public record ChannelSummaryDto(
            long id, String channelType, String channelKey, String name, long unreadCount,
            String lastMessagePreview, String lastMessageUtc, Integer peerUserId,
            String peerStatus, String peerCustomNote, List<MemberDto> members
    ) {}

    public record MemberDto(
            int userId, String username, String fullName, String role,
            String memberRole, String status, long lastReadMessageId
    ) {}

    public record AttachmentDto(
            long id, long messageId, long channelId, String fileName,
            String contentType, long fileSize, boolean image, String uploadedUtc
    ) {}

    public record MessageDto(
            long id, long channelId, int senderId, String senderUsername, String senderName, String senderRole,
            String body, Long replyToId, String replyPreview, String linkedDocType, String linkedDocNo,
            Map<String, List<Integer>> reactions, List<AttachmentDto> attachments,
            String createdUtc, String editedUtc, boolean deleted, boolean readByPeer
    ) {}

    public record ChatStreamEvent(
            String eventType, Long channelId, MessageDto message, PresenceDto presence,
            Integer actorUserId, String actorName, Boolean typing, Long readMessageId
    ) {}

    public record PresenceUpdateRequest(String status, String customNote) {}
    public record OpenDirectRequest(int peerUserId) {}
    public record CreateGroupRequest(String name, List<Integer> memberIds) {}
    public record SendMessageRequest(String body, Long replyToId, String linkedDocType, String linkedDocNo) {}
    public record EditMessageRequest(String body) {}
    public record ReactRequest(String emoji) {}
    public record MarkReadRequest(long lastReadMessageId) {}
    public record TypingRequest(boolean typing) {}
}
