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
import java.time.Instant;
import java.util.Map;

public class LicenseApiClient {

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record LicenseStatusDto(
            String status,
            String licenseKey,
            String companyName,
            String machineId,
            String currentMachineId,
            String tier,
            Instant validUntil,
            long daysRemaining,
            boolean isWriteAllowed,
            String message,
            java.util.List<String> features
    ) {
        public LicenseStatusDto(String status, String licenseKey, String companyName, String machineId,
                                String currentMachineId, String tier, Instant validUntil, long daysRemaining,
                                boolean isWriteAllowed, String message) {
            this(status, licenseKey, companyName, machineId, currentMachineId, tier, validUntil, daysRemaining, isWriteAllowed, message, java.util.List.of());
        }
    }

    private final HttpClient http = ApiRuntime.HTTP;
    private final ObjectMapper json = ApiRuntime.JSON;
    private final String base;

    public LicenseApiClient() {
        String b = ConfigManager.getDataApiBaseUrl();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.base = b;
    }

    public LicenseStatusDto getStatus() {
        return get("/api/license/status", LicenseStatusDto.class);
    }

    public Map<String, Object> activate(Object payload) {
        return post("/api/license/activate", payload, new TypeReference<>() {});
    }

    public Map<String, Object> getUpiPayload(String amount) {
        return get("/api/license/upi-payload?amount=" + (amount == null ? "3500.00" : amount.trim()), new TypeReference<>() {});
    }

    public Map<String, Object> issueClientLicense(Object request) {
        return post("/api/license/super/issue", request, new TypeReference<>() {});
    }

    public Map<String, Object> getCatalog() {
        try {
            return get("/api/license/catalog", new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    public Map<String, Object> selfRegister(Map<String, Object> req) {
        return post("/api/license/self-register", req, new TypeReference<>() {});
    }

    public Map<String, Object> checkLead(String machineId) {
        String enc = java.net.URLEncoder.encode(machineId == null ? "" : machineId, StandardCharsets.UTF_8);
        return get("/api/license/check-lead?machineId=" + enc, new TypeReference<>() {});
    }

    private <T> T get(String path, Class<T> clazz) {
        return req("GET", path, null, clazz, null);
    }

    private <T> T get(String path, TypeReference<T> typeRef) {
        return req("GET", path, null, null, typeRef);
    }

    private <T> T post(String path, Object body, TypeReference<T> typeRef) {
        return req("POST", path, body, null, typeRef);
    }

    private <T> T req(String method, String path, Object body, Class<T> clazz, TypeReference<T> typeRef) {
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
                throw new IllegalStateException(ApiRuntime.userMessage("Licensing", resp.statusCode(), resp.body()));
            }

            return typeRef != null ? json.readValue(resp.body(), typeRef) : json.readValue(resp.body(), clazz);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot reach server licensing API at " + base, e);
        }
    }
}
