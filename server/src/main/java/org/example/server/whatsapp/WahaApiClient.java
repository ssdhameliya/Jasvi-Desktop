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
        if (clean.contains("144.24.109.1") || clean.contains("130.210.35.1")) {
            clean = clean.replace("144.24.109.1", "127.0.0.1").replace("130.210.35.1", "127.0.0.1");
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
            log.info("WAHA list sessions notice: {}", e.getMessage());
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
            return (res.statusCode() >= 200 && res.statusCode() < 300) || res.statusCode() == 422;
        } catch (Exception e) {
            log.warn("Failed to start WAHA session {}: {}", session, e.getMessage());
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

                if ("STARTING".equalsIgnoreCase(current.status())) {
                    // Return fast so client poll timeline can smoothly update without hitting HTTP timeouts
                    return new WahaDtos.QrResponse(session, null, "STARTING", "WhatsApp Web is starting up. Preparing QR code...");
                }

                // Request QR code image/png with ?format=image
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
                } else if (res.statusCode() == 422) {
                    // Session is transitioning
                    return new WahaDtos.QrResponse(session, null, "STARTING", "WhatsApp session is initializing. Preparing QR code...");
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
        return new WahaDtos.QrResponse(session, null, "STARTING", "WhatsApp Web is starting up. Preparing QR code...");
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
            } else if (res.statusCode() == 422 && res.body().contains("STARTING")) {
                return new WahaDtos.SendResponse(false, "WhatsApp sender channel is not paired or ready. Please scan the QR code in Settings > WhatsApp to authenticate with your phone.", "", session, chatId);
            } else {
                return new WahaDtos.SendResponse(false, "WAHA error (" + res.statusCode() + "): " + res.body(), "", session, chatId);
            }
        } catch (Exception e) {
            log.error("Failed to send WAHA text to {}: {}", chatId, e.getMessage());
            return new WahaDtos.SendResponse(false, "Connection error: " + e.getMessage(), "", session, chatId);
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
            } else if (res.statusCode() == 422 && res.body().contains("STARTING")) {
                return new WahaDtos.SendResponse(false, "WhatsApp sender channel is not paired or ready. Please scan the QR code in Settings > WhatsApp to authenticate with your phone.", "", session, chatId);
            } else {
                return new WahaDtos.SendResponse(false, "WAHA error (" + res.statusCode() + "): " + res.body(), "", session, chatId);
            }
        } catch (Exception e) {
            log.error("Failed to send WAHA file to {}: {}", chatId, e.getMessage());
            return new WahaDtos.SendResponse(false, "Connection error: " + e.getMessage(), "", session, chatId);
        }
    }
}
