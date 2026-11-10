package org.example.api.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.api.ApiRuntime;
import org.example.api.ApiSession;
import org.example.config.ConfigManager;
import org.example.shared.sync.SyncBatchRequest;
import org.example.shared.sync.SyncBatchResult;
import org.example.util.DesktopLog;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * High-performance REST client for Cloud Synchronization.
 * Communicates with the cloud Spring Boot server over HTTPS with full TLS protection.
 */
public final class SyncApiClient {

    private final HttpClient http = ApiRuntime.HTTP;
    private final ObjectMapper json = ApiRuntime.JSON;
    private final String base;

    public SyncApiClient() {
        String b = ConfigManager.getDataApiBaseUrl();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.base = b;
    }

    public SyncApiClient(String customBaseUrl) {
        String b = customBaseUrl == null ? ConfigManager.getDataApiBaseUrl() : customBaseUrl;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.base = b;
    }

    /**
     * Probes if the cloud server is reachable within 2.5 seconds.
     */
    public boolean isCloudReachable() {
        if (base == null || base.isBlank()) return false;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + "/api/sync/v1/health"))
                .timeout(Duration.ofMillis(2500))
                .GET()
                .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Pushes a batch of offline transactions to the cloud server.
     */
    public SyncBatchResult pushBatch(SyncBatchRequest request) throws IOException, InterruptedException {
        String payload = json.writeValueAsString(request);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(base + "/api/sync/v1/push"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(payload));

        if (ApiSession.isEstablished()) {
            builder = ApiSession.authorize(builder);
        }

        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401) {
            ApiSession.clear();
            throw new IOException("Sync session expired (HTTP 401)");
        }
        if (response.statusCode() != 200) {
            throw new IOException("Sync batch rejected with HTTP " + response.statusCode() + ": " + response.body());
        }
        return json.readValue(response.body(), SyncBatchResult.class);
    }

    /**
     * Fetches server synchronization status summary.
     */
    public Map<String, Object> getStatus() {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(base + "/api/sync/v1/status"))
                .timeout(Duration.ofSeconds(5))
                .GET();
            if (ApiSession.isEstablished()) {
                builder = ApiSession.authorize(builder);
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return json.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
            }
        } catch (Exception e) {
            DesktopLog.warn("SyncApiClient", "STATUS_ERROR", "Failed to retrieve sync status: " + e.getMessage());
        }
        return Map.of("isHealthy", false, "error", "Unreachable");
    }
}
