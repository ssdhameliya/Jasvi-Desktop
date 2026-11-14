package org.example.api.licensing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.api.ApiRuntime;
import org.example.api.ApiSession;
import org.example.config.ConfigManager;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class ClientRegistryApiClient {

    private final HttpClient http = ApiRuntime.HTTP;
    private final ObjectMapper json = ApiRuntime.JSON;
    private final String base;

    public ClientRegistryApiClient() {
        String b = ConfigManager.getDataApiBaseUrl();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.base = b;
    }

    public List<Map<String, Object>> getClients(String query, String status) {
        StringBuilder url = new StringBuilder("/api/license/registry/clients?status=" + (status != null ? enc(status) : "ALL"));
        if (query != null && !query.isBlank()) {
            url.append("&query=").append(enc(query.trim()));
        }
        return get(url.toString(), new TypeReference<>() {});
    }

    public Map<String, Object> getClientById(Long id) {
        return get("/api/license/registry/clients/" + id, new TypeReference<>() {});
    }

    public Map<String, Object> registerClient(Map<String, Object> req) {
        return post("/api/license/registry/clients", req, new TypeReference<>() {});
    }

    public Map<String, Object> renewClient(Long id, int days) {
        return post("/api/license/registry/renew/" + id, Map.of("days", days), new TypeReference<>() {});
    }

    public Map<String, Object> revokeClient(Long id) {
        return post("/api/license/registry/revoke/" + id, Map.of(), new TypeReference<>() {});
    }

    public Map<String, Object> getStats() {
        return get("/api/license/registry/stats", new TypeReference<>() {});
    }

    public byte[] downloadLicenseFile(Long id) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/api/license/registry/download/" + id))
                    .timeout(Duration.ofSeconds(15));
            ApiSession.authorize(b);
            HttpResponse<byte[]> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + resp.statusCode() + " downloading license file");
            }
            return resp.body();
        } catch (Exception e) {
            throw new RuntimeException("Error downloading license file: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> getCommercialSettings() {
        return get("/api/license/registry/commercial-settings", new TypeReference<>() {});
    }

    public Map<String, Object> saveCommercialSettings(Map<String, Object> settings) {
        return post("/api/license/registry/commercial-settings", settings, new TypeReference<>() {});
    }

    public List<Map<String, Object>> getPlans() {
        return get("/api/license/registry/plans", new TypeReference<>() {});
    }

    public Map<String, Object> savePlan(Map<String, Object> plan) {
        return post("/api/license/registry/plans", plan, new TypeReference<>() {});
    }

    public Map<String, Object> deletePlan(Long id) {
        return req("DELETE", "/api/license/registry/plans/" + id, null, new TypeReference<>() {});
    }

    public List<Map<String, Object>> getLeads() {
        return get("/api/license/registry/leads", new TypeReference<>() {});
    }

    public Map<String, Object> approveLead(Long leadId, String tier, Integer days) {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        if (tier != null) body.put("tier", tier);
        if (days != null) body.put("days", days);
        return post("/api/license/registry/leads/" + leadId + "/approve", body, new TypeReference<>() {});
    }

    public Map<String, Object> rejectLead(Long leadId) {
        return post("/api/license/registry/leads/" + leadId + "/reject", Map.of(), new TypeReference<>() {});
    }

    public Map<String, Object> deleteLead(Long leadId) {
        return req("DELETE", "/api/license/registry/leads/" + leadId, null, new TypeReference<>() {});
    }

    public Map<String, Object> dispatchChannels(Long clientId) {
        return post("/api/license/registry/clients/" + clientId + "/dispatch", Map.of(), new TypeReference<>() {});
    }

    public Map<String, Object> dispatchEmail(Long clientId) {
        return post("/api/license/registry/clients/" + clientId + "/dispatch-email", Map.of(), new TypeReference<>() {});
    }

    public Map<String, Object> dispatchWhatsApp(Long clientId) {
        return post("/api/license/registry/clients/" + clientId + "/dispatch-whatsapp", Map.of(), new TypeReference<>() {});
    }

    public Map<String, Object> sendHeartbeat(String machineId, String clientVersion, Integer activeUsers, String remoteUrl) {
        return sendHeartbeat(machineId, clientVersion, activeUsers, remoteUrl, null, null);
    }

    public Map<String, Object> sendHeartbeat(String machineId, String clientVersion, Integer activeUsers, String remoteUrl,
                                             String companyName, List<Map<String, Object>> users) {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        if (machineId != null) body.put("machineId", machineId);
        if (clientVersion != null) body.put("clientVersion", clientVersion);
        if (activeUsers != null) body.put("activeUsers", activeUsers);
        if (remoteUrl != null) body.put("remoteUrl", remoteUrl);
        if (companyName != null) body.put("companyName", companyName);
        if (users != null && !users.isEmpty()) body.put("users", users);
        return post("/api/license/heartbeat", body, new TypeReference<>() {});
    }

    public List<Map<String, Object>> getClientUsers(String machineId) {
        return getClientUsers(machineId, null);
    }

    public List<Map<String, Object>> getClientUsers(String machineId, String date) {
        String query = (date != null && !date.isBlank()) ? "?date=" + enc(date) : "";
        return get("/api/license/registry/clients/" + enc(machineId) + "/users" + query, new TypeReference<>() {});
    }

    public Map<String, Object> releaseWorkstation(String machineId) {
        return post("/api/license/registry/clients/" + enc(machineId) + "/release", Map.of(), new TypeReference<>() {});
    }

    public Map<String, Object> kickClientUser(String machineId, String username) {
        return post("/api/license/registry/clients/" + machineId + "/users/" + username + "/kick", Map.of(), new TypeReference<>() {});
    }

    public Map<String, Object> lockClientUser(String machineId, String username) {
        return post("/api/license/registry/clients/" + machineId + "/users/" + username + "/lock", Map.of(), new TypeReference<>() {});
    }

    public Map<String, Object> unlockClientUser(String machineId, String username) {
        return post("/api/license/registry/clients/" + machineId + "/users/" + username + "/unlock", Map.of(), new TypeReference<>() {});
    }

    public Map<String, Object> setUserActiveStatus(String machineId, String username, boolean active) {
        return post("/api/license/registry/clients/" + machineId + "/users/" + username + "/active?active=" + active, Map.of(), new TypeReference<>() {});
    }

    public Map<String, Object> triggerTestRogueAlert() {
        return post("/api/license/super/test-rogue-alert", Map.of(), new TypeReference<>() {});
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
                throw new IllegalStateException(ApiRuntime.userMessage("ClientRegistry", resp.statusCode(), resp.body()));
            }

            return json.readValue(resp.body(), typeRef);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot reach server registry API at " + base, e);
        }
    }

    private static String enc(String val) {
        return URLEncoder.encode(val, StandardCharsets.UTF_8);
    }
}
