package org.example.api.licensing;

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
import java.util.Map;

public class RemoteAccessApiClient {

    private final HttpClient http = ApiRuntime.HTTP;
    private final ObjectMapper json = ApiRuntime.JSON;
    private final String base;

    public RemoteAccessApiClient() {
        String b = ConfigManager.getDataApiBaseUrl();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.base = b;
    }

    public Map<String, Object> getStatus() {
        return get("/api/remote-access/status", new TypeReference<>() {});
    }

    public Map<String, Object> startTunnel() {
        int port = extractPort(this.base, 58080);
        return startTunnel(port);
    }

    private int extractPort(String url, int defaultPort) {
        if (url == null || url.isBlank()) {
            return defaultPort;
        }
        try {
            URI u = URI.create(url);
            int p = u.getPort();
            return p > 0 ? p : defaultPort;
        } catch (Exception e) {
            return defaultPort;
        }
    }

    public Map<String, Object> startTunnel(int port) {
        return post("/api/remote-access/start?port=" + port, null, new TypeReference<>() {});
    }

    public Map<String, Object> stopTunnel() {
        return post("/api/remote-access/stop", null, new TypeReference<>() {});
    }

    public byte[] getQrCodePng() {
        return getQrImageBytes();
    }

    public byte[] getQrImageBytes() {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/api/remote-access/qr-image"))
                    .timeout(Duration.ofSeconds(10));
            ApiSession.authorize(b);
            HttpResponse<byte[]> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 200) {
                return resp.body();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private <T> T get(String path, TypeReference<T> typeRef) {
        return req("GET", path, null, typeRef);
    }

    private <T> T post(String path, Object body, TypeReference<T> typeRef) {
        return req("POST", path, body, typeRef);
    }

    private <T> T req(String method, String path, Object body, TypeReference<T> typeRef) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json");
            ApiSession.authorize(b);

            if (body != null) {
                b.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }

            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException(ApiRuntime.userMessage("RemoteAccess", resp.statusCode(), resp.body()));
            }

            return json.readValue(resp.body(), typeRef);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot reach remote access API at " + base, e);
        }
    }
}
