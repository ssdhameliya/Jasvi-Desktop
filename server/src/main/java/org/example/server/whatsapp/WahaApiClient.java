package org.example.server.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Component
public class WahaApiClient {
    private static final Logger log = LoggerFactory.getLogger(WahaApiClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient http;
    private final ObjectMapper json;

    public WahaApiClient() {
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.json = new ObjectMapper();
    }

    private String normalizeBaseUrl(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "http://localhost:3000";
        }
        String clean = endpoint.trim();
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "http://" + clean;
        }
        return clean;
    }

    private HttpRequest.Builder request(String endpoint, String path, String apiKey) {
        String base = normalizeBaseUrl(endpoint);
        String url = base + (path.startsWith("/") ? path : "/" + path);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json, image/png, text/plain");
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("X-Api-Key", apiKey.trim());
        }
        return builder;
    }

    private void tryAutoStartDocker() {
        try {
            Process p = new ProcessBuilder("docker", "--version").start();
            if (p.waitFor() == 0) {
                Process inspect = new ProcessBuilder("docker", "inspect", "waha").start();
                if (inspect.waitFor() == 0) {
                    new ProcessBuilder("docker", "start", "waha").start();
                } else {
                    String osArch = System.getProperty("os.arch", "").toLowerCase();
                    String img = (osArch.contains("arm") || osArch.contains("aarch64")) ? "devlikeapro/waha:arm" : "devlikeapro/waha";
                    new ProcessBuilder("docker", "run", "-d", "--name", "waha", "-p", "3000:3000", "--restart", "always",
                            "-e", "WAHA_NO_API_KEY=true",
                            "-e", "WAHA_DASHBOARD_NO_PASSWORD=true",
                            "-e", "WHATSAPP_SWAGGER_NO_PASSWORD=true",
                            img).start();
                }
            }
        } catch (Exception ignored) {}
    }

    public WahaDtos.HealthResponse ping(String endpoint, String apiKey) {
        try {
            String version = "Unknown";
            HttpResponse<String> res = null;
            for (String path : List.of("/api/server/version", "/api/version")) {
                try {
                    HttpRequest req = request(endpoint, path, apiKey).GET().build();
                    HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
                    if (r.statusCode() >= 200 && r.statusCode() < 300) {
                        res = r;
                        break;
                    } else if (r.statusCode() == 401 || r.statusCode() == 403) {
                        return new WahaDtos.HealthResponse(false, "Authentication failed (Invalid WAHA API Key)", "", List.of());
                    }
                } catch (Exception ignored) {}
            }

            if (res != null && res.statusCode() >= 200 && res.statusCode() < 300) {
                try {
                    JsonNode root = json.readTree(res.body());
                    if (root.has("version")) version = root.get("version").asText();
                    else if (root.isTextual()) version = root.asText();
                    else version = res.body();
                } catch (Exception ignored) {
                    version = res.body();
                }
            }

            List<WahaDtos.SessionStatus> sessions = getSessions(endpoint, apiKey);
            if (!"Unknown".equals(version) || !sessions.isEmpty()) {
                return new WahaDtos.HealthResponse(true, "WAHA Connected", version, sessions);
            }
            // Check if sessions endpoint at least replied
            HttpRequest sessReq = request(endpoint, "/api/sessions?all=true", apiKey).GET().build();
            HttpResponse<String> sessRes = http.send(sessReq, HttpResponse.BodyHandlers.ofString());
            if (sessRes.statusCode() >= 200 && sessRes.statusCode() < 300) {
                return new WahaDtos.HealthResponse(true, "WAHA Connected", version, sessions);
            }
            return new WahaDtos.HealthResponse(false, "WhatsApp engine is offline at " + endpoint + ".\n\nWAHA Docker container is not running on port 3000.\nTo start it, run: docker run -d --name waha -p 3000:3000 devlikeapro/waha\nor click 'Start WAHA Engine' in Settings.", "", List.of());
        } catch (Exception e) {
            String norm = normalizeBaseUrl(endpoint);
            if (norm.contains("localhost") || norm.contains("127.0.0.1")) {
                tryAutoStartDocker();
            }
            log.info("WAHA ping notice for {}: {}", endpoint, e.getMessage());
            return new WahaDtos.HealthResponse(false, "WhatsApp engine is offline at " + endpoint + ".\n\nWAHA Docker container is not running on port 3000.\nTo start it, run: docker run -d --name waha -p 3000:3000 devlikeapro/waha\nor click 'Start WAHA Engine' in Settings.", "", List.of());
        }
    }

    public List<WahaDtos.SessionStatus> getSessions(String endpoint, String apiKey) {
        try {
            HttpRequest req = request(endpoint, "/api/sessions?all=true", apiKey)
                    .GET()
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                JsonNode root = json.readTree(res.body());
                List<WahaDtos.SessionStatus> list = new ArrayList<>();
                if (root.isArray()) {
                    for (JsonNode item : root) {
                        String name = item.path("name").asText("default");
                        String status = item.path("status").asText("UNKNOWN");
                        list.add(new WahaDtos.SessionStatus(name, status));
                    }
                }
                return list;
            }
        } catch (Exception e) {
            log.info("WAHA list sessions notice: {}", errorDetail(e));
        }
        return List.of();
    }

    public boolean startSession(String endpoint, String apiKey, String sessionName) {
        String session = (sessionName == null || sessionName.isBlank()) ? "default" : sessionName.trim();
        try {
            // First check if session exists
            List<WahaDtos.SessionStatus> sessions = getSessions(endpoint, apiKey);
            boolean exists = sessions.stream().anyMatch(s -> session.equalsIgnoreCase(s.name()));
            if (exists) {
                // If it already exists, use POST /api/sessions/{session}/start
                HttpRequest startReq = request(endpoint, "/api/sessions/" + session + "/start", apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();
                HttpResponse<String> startRes = http.send(startReq, HttpResponse.BodyHandlers.ofString());
                if (startRes.statusCode() >= 200 && startRes.statusCode() < 300) {
                    return true;
                }
            }
            // If not existing or start failed, create session
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", session);
            body.put("start", true);
            HttpRequest req = request(endpoint, "/api/sessions", apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            
            // Explicitly start the session to trigger headless browser launch
            try {
                HttpRequest startReq = request(endpoint, "/api/sessions/" + session + "/start", apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();
                http.send(startReq, HttpResponse.BodyHandlers.ofString());
            } catch (Exception ignored) {}

            return (res.statusCode() >= 200 && res.statusCode() < 300) || res.statusCode() == 422;
        } catch (Exception e) {
            log.warn("Failed to start WAHA session {}: {}", session, errorDetail(e));
            return false;
        }
    }

    public WahaDtos.QrResponse getQrCode(String endpoint, String apiKey, String sessionName) {
        String session = (sessionName == null || sessionName.isBlank()) ? "default" : sessionName.trim();
        try {
            // Check session status with quick non-blocking attempts (avoiding reverse proxy 30s timeout)
            for (int attempt = 0; attempt < 2; attempt++) {
                List<WahaDtos.SessionStatus> sessions = getSessions(endpoint, apiKey);
                WahaDtos.SessionStatus current = sessions.stream()
                        .filter(s -> session.equalsIgnoreCase(s.name()))
                        .findFirst()
                        .orElse(null);

                if (current != null && "WORKING".equalsIgnoreCase(current.status())) {
                    return new WahaDtos.QrResponse(session, null, "WORKING", "WhatsApp session is already active and paired.");
                }

                if (current != null && "FAILED".equalsIgnoreCase(current.status())) {
                    try {
                        HttpRequest restartReq = request(endpoint, "/api/sessions/" + session + "/restart", apiKey)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build();
                        http.send(restartReq, HttpResponse.BodyHandlers.ofString());
                    } catch (Exception ignored) {}
                    Thread.sleep(800);
                    continue;
                }
                if (current == null || "STOPPED".equalsIgnoreCase(current.status())) {
                    startSession(endpoint, apiKey, session);
                    Thread.sleep(800);
                    continue;
                }

                // First attempt to fetch the QR image directly (works even during transition to SCAN_QR_CODE)
                HttpRequest req = request(endpoint, "/api/" + session + "/auth/qr?format=image", apiKey)
                        .header("Accept", "image/png, application/json, */*")
                        .GET()
                        .build();
                HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());

                if (res.statusCode() >= 200 && res.statusCode() < 300) {
                    String contentType = res.headers().firstValue("Content-Type").orElse("");
                    if (contentType.contains("image") || (res.body() != null && res.body().length > 100)) {
                        String base64 = "data:image/png;base64," + Base64.getEncoder().encodeToString(res.body());
                        return new WahaDtos.QrResponse(session, base64, "SCAN_QR_CODE", "Please scan this QR code with WhatsApp on your phone.");
                    } else {
                        String text = new String(res.body(), java.nio.charset.StandardCharsets.UTF_8);
                        try {
                            JsonNode node = json.readTree(text);
                            if (node.has("data")) {
                                String data = node.get("data").asText();
                                if (!data.startsWith("data:")) data = "data:image/png;base64," + data;
                                return new WahaDtos.QrResponse(session, data, "SCAN_QR_CODE", "Please scan this QR code with WhatsApp.");
                            } else if (node.has("raw") || node.has("qr")) {
                                return new WahaDtos.QrResponse(session, node.path("raw").asText(node.path("qr").asText()), "RAW_QR", "Scan QR string.");
                            }
                        } catch (Exception ignored) {}
                    }
                } else if (res.statusCode() == 422 || (current != null && "STARTING".equalsIgnoreCase(current.status()))) {
                    // Session is booting headless browser
                    return new WahaDtos.QrResponse(session, null, "ENGINE_STARTING", "WhatsApp Web engine is starting up. Preparing pairing QR code...");
                } else if (res.statusCode() == 404 || res.statusCode() == 400) {
                    return new WahaDtos.QrResponse(session, null, "ALREADY_CONNECTED", "Session is already connected or QR code is not pending.");
                }
            }
        } catch (Exception e) {
            String norm = normalizeBaseUrl(endpoint);
            if (norm.contains("localhost") || norm.contains("127.0.0.1")) {
                tryAutoStartDocker();
            }
            log.info("WAHA QR notice for {}: {}", session, e.getMessage());
            return new WahaDtos.QrResponse(session, null, "OFFLINE", "WhatsApp engine (WAHA) is not running on the server. Please ensure the server container is started.");
        }
        return new WahaDtos.QrResponse(session, null, "ENGINE_STARTING", "WhatsApp Web engine is starting up. Preparing pairing QR code...");
    }

    public WahaDtos.SendResponse sendText(String endpoint, String apiKey, String sessionName, String chatId, String text) {
        String session = (sessionName == null || sessionName.isBlank()) ? "default" : sessionName.trim();
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("session", session);
            payload.put("chatId", chatId);
            payload.put("text", text);

            HttpRequest req = request(endpoint, "/api/sendText", apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 422 && res.body().contains("STARTING")) {
                // Session is momentarily initializing; wait 1.5s and retry once
                try {
                    Thread.sleep(1500);
                    res = http.send(req, HttpResponse.BodyHandlers.ofString());
                } catch (Exception ignored) {}
            }

            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                String messageId = "";
                try {
                    JsonNode node = json.readTree(res.body());
                    messageId = node.path("id").asText(node.path("messageId").asText(""));
                } catch (Exception ignored) {}
                return new WahaDtos.SendResponse(true, "Message sent successfully", messageId, session, chatId);
            } else if (res.statusCode() == 422 && (res.body().contains("STARTING") || res.body().contains("does not exist") || res.body().contains("not exist") || res.body().contains("Session not found"))) {
                try {
                    startSession(endpoint, apiKey, session);
                } catch (Exception ignored) {}
                return new WahaDtos.SendResponse(false, "WhatsApp sender channel is not paired or ready. The session has been auto-initialized in WAHA; please open Settings > WhatsApp to scan the QR code with your phone to connect.", "", session, chatId);
            } else {
                return new WahaDtos.SendResponse(false, extractCleanErrorMessage(res.statusCode(), res.body(), chatId), "", session, chatId);
            }
        } catch (Exception e) {
            String err = errorDetail(e);
            log.error("Failed to send WAHA text to {}: {}", chatId, err);
            return new WahaDtos.SendResponse(false, "Connection error: " + err, "", session, chatId);
        }
    }

    public WahaDtos.SendResponse sendFile(String endpoint, String apiKey, String sessionName, String chatId,
                                         String filename, String mimetype, byte[] fileBytes, String caption) {
        String session = (sessionName == null || sessionName.isBlank()) ? "default" : sessionName.trim();
        try {
            Map<String, Object> fileObj = new LinkedHashMap<>();
            fileObj.put("mimetype", (mimetype == null || mimetype.isBlank()) ? "application/pdf" : mimetype);
            fileObj.put("filename", (filename == null || filename.isBlank()) ? "document.pdf" : filename);
            fileObj.put("data", Base64.getEncoder().encodeToString(fileBytes));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("session", session);
            payload.put("chatId", chatId);
            payload.put("file", fileObj);
            if (caption != null && !caption.isBlank()) {
                payload.put("caption", caption);
            }

            HttpRequest req = request(endpoint, "/api/sendFile", apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 422 && res.body().contains("STARTING")) {
                // Session is momentarily initializing; wait 1.5s and retry once
                try {
                    Thread.sleep(1500);
                    res = http.send(req, HttpResponse.BodyHandlers.ofString());
                } catch (Exception ignored) {}
            }

            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                String messageId = "";
                try {
                    JsonNode node = json.readTree(res.body());
                    messageId = node.path("id").asText(node.path("messageId").asText(""));
                } catch (Exception ignored) {}
                return new WahaDtos.SendResponse(true, "Document sent successfully via WhatsApp", messageId, session, chatId);
            } else if (res.statusCode() == 422 && (res.body().contains("STARTING") || res.body().contains("does not exist") || res.body().contains("not exist") || res.body().contains("Session not found"))) {
                try {
                    startSession(endpoint, apiKey, session);
                } catch (Exception ignored) {}
                return new WahaDtos.SendResponse(false, "WhatsApp sender channel is not paired or ready. The session has been auto-initialized in WAHA; please open Settings > WhatsApp to scan the QR code with your phone to connect.", "", session, chatId);
            } else {
                return new WahaDtos.SendResponse(false, extractCleanErrorMessage(res.statusCode(), res.body(), chatId), "", session, chatId);
            }
        } catch (Exception e) {
            String err = errorDetail(e);
            log.error("Failed to send WAHA file to {}: {}", chatId, err);
            return new WahaDtos.SendResponse(false, "Connection error: " + err, "", session, chatId);
        }
    }

    private static String errorDetail(Exception e) {
        if (e == null) return "Unknown error";
        String msg = e.getMessage();
        return msg != null && !msg.isBlank() ? msg : e.getClass().getSimpleName();
    }

    private String extractCleanErrorMessage(int statusCode, String body, String chatId) {
        if (body == null || body.isBlank()) {
            return "WhatsApp engine is not responding (HTTP " + statusCode + "). Please verify that WAHA service is running in Settings > WhatsApp.";
        }

        // 1. HTTP 301/302/307/308 or redirect to /login (dashboard auth / session unauthenticated)
        if (statusCode == 301 || statusCode == 302 || statusCode == 307 || statusCode == 308
                || body.contains("/login") || body.contains("login?next")) {
            return "WhatsApp is not connected or requires authentication. Please open Settings > WhatsApp to check your connection and scan the QR code with your phone.";
        }

        // 2. HTTP 401/403: Invalid API key or unauthorized
        if (statusCode == 401 || statusCode == 403) {
            return "WhatsApp authentication failed: Invalid or missing WAHA API Key. Please verify your credentials in Settings > WhatsApp.";
        }

        // 3. HTTP 422: Session starting, stopped, or not paired
        if (statusCode == 422 && body.contains("STARTING")) {
            return "WhatsApp sender channel is still initializing. Please wait a moment and try again, or scan the QR code in Settings > WhatsApp.";
        }

        // 4. Session stopped / not found / does not exist
        if (body.contains("does not exist") || body.contains("not exist")
                || body.contains("Session not found") || body.contains("STOPPED") || body.contains("FAILED")) {
            return "WhatsApp session is not paired or does not exist in the WhatsApp engine. Please open Settings > WhatsApp, start the channel, and scan the QR code with your phone.";
        }

        // 5. Service unreachable
        if (statusCode == 502 || statusCode == 503 || statusCode == 504) {
            return "WhatsApp service is temporarily unreachable (HTTP " + statusCode + "). Please check your WAHA server status in Settings > WhatsApp.";
        }

        String cleanPhone = chatId != null ? chatId.replace("@c.us", "").replace("@lid", "") : "";
        try {
            JsonNode root = json.readTree(body);
            String exMsg = root.path("exception").path("message").asText("");
            if (exMsg.isBlank()) {
                exMsg = root.path("message").asText("");
            }
            if (exMsg.contains("No LID for user")) {
                return "The recipient phone number (" + cleanPhone + ") is not registered on WhatsApp or has an invalid number format. Please verify the mobile number in Customer Master.";
            }
            if (exMsg.contains("does not exist") || exMsg.contains("not exist") || exMsg.contains("Session not found") || exMsg.contains("STOPPED")) {
                return "WhatsApp session is not paired or does not exist in the WhatsApp engine. Please open Settings > WhatsApp, start the channel, and scan the QR code with your phone.";
            }
            if (!exMsg.isBlank()) {
                int nl = exMsg.indexOf('\n');
                String firstLine = nl > 0 ? exMsg.substring(0, nl).trim() : exMsg.trim();
                return "WAHA: " + firstLine;
            }
        } catch (Exception ignored) {}

        if (body.contains("No LID for user")) {
            return "The recipient phone number (" + cleanPhone + ") is not registered on WhatsApp or has an invalid number format. Please verify the mobile number in Customer Master.";
        }

        // Safeguard: Never store or echo multi-megabyte base64 payloads into logs
        if (body.length() > 300) {
            return "WAHA error (HTTP " + statusCode + "): " + body.substring(0, 300) + "...";
        }
        return "WAHA error (HTTP " + statusCode + "): " + body;
    }
}
