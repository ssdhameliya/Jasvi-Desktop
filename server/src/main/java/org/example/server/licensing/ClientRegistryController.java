package org.example.server.licensing;

import org.example.server.security.CurrentUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/license/registry")
public class ClientRegistryController {

    private final ClientRegistryService registryService;

    public ClientRegistryController(ClientRegistryService registryService) {
        this.registryService = registryService;
    }

    private void requireSuperAdmin() {
        var user = CurrentUser.require();
        if (!"SUPER_ADMIN".equalsIgnoreCase(user.role())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Access restricted strictly to Platform Super Admin"
            );
        }
    }

    @GetMapping("/clients")
    public List<Map<String, Object>> getClients(
            @RequestParam(required = false) String query,
            @RequestParam(required = false, defaultValue = "ALL") String status
    ) {
        requireSuperAdmin();
        return registryService.getClients(query, status);
    }

    @GetMapping("/clients/{id}")
    public Map<String, Object> getClientById(@PathVariable Long id) {
        requireSuperAdmin();
        return registryService.getClientById(id);
    }

    @PostMapping("/clients")
    public Map<String, Object> registerClient(@RequestBody Map<String, Object> clientReq) {
        requireSuperAdmin();
        return registryService.registerClient(clientReq);
    }

    @PostMapping("/renew/{id}")
    public Map<String, Object> renewClient(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body
    ) {
        requireSuperAdmin();
        int days = 365;
        if (body != null && body.get("days") instanceof Number n) {
            days = n.intValue();
        }
        return registryService.renewClient(id, days);
    }

    @PostMapping("/revoke/{id}")
    public ResponseEntity<?> revokeClient(@PathVariable Long id) {
        requireSuperAdmin();
        boolean ok = registryService.revokeClient(id);
        return ResponseEntity.ok(Map.of("success", ok, "id", id, "status", "REVOKED"));
    }

    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        requireSuperAdmin();
        return registryService.getStats();
    }

    @GetMapping("/download/{id}")
    public ResponseEntity<byte[]> downloadLicenseFile(@PathVariable Long id) {
        requireSuperAdmin();
        Map<String, Object> client = registryService.getClientById(id);
        String licenseJson = String.valueOf(client.get("license_payload_enc"));
        String company = String.valueOf(client.getOrDefault("company_name", "client"))
                .replaceAll("[^a-zA-Z0-9_-]", "_");

        byte[] bytes = licenseJson.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + company + "-license.jlic\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes);
    }

    @GetMapping("/commercial-settings")
    public Map<String, Object> getCommercialSettings() {
        requireSuperAdmin();
        return registryService.getCommercialSettings();
    }

    @PostMapping("/commercial-settings")
    public ResponseEntity<?> saveCommercialSettings(@RequestBody Map<String, Object> settings) {
        requireSuperAdmin();
        registryService.saveCommercialSettings(settings);
        return ResponseEntity.ok(Map.of("success", true, "message", "Commercial settings saved successfully"));
    }

    @GetMapping("/plans")
    public List<Map<String, Object>> getPlans() {
        requireSuperAdmin();
        return registryService.getPlans();
    }

    @PostMapping("/plans")
    public Map<String, Object> savePlan(@RequestBody Map<String, Object> plan) {
        requireSuperAdmin();
        return registryService.savePlan(plan);
    }

    @DeleteMapping("/plans/{id}")
    public Map<String, Object> deletePlan(@PathVariable Long id) {
        requireSuperAdmin();
        return registryService.deletePlan(id);
    }

    @GetMapping("/leads")
    public List<Map<String, Object>> getLeads() {
        requireSuperAdmin();
        return registryService.getLeads();
    }

    @PostMapping("/leads/{id}/approve")
    public Map<String, Object> approveLead(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body
    ) {
        requireSuperAdmin();
        String tier = body != null && body.get("tier") != null ? String.valueOf(body.get("tier")) : null;
        Integer days = body != null && body.get("days") instanceof Number n ? n.intValue() : null;
        Map<String, Object> client = registryService.approveLead(id, tier, days);
        Map<String, Object> dispatch = registryService.dispatchLicenseChannels(client);
        Map<String, Object> result = new java.util.LinkedHashMap<>(client);
        result.put("dispatch", dispatch);
        return result;
    }

    @PostMapping("/clients/{id}/dispatch")
    public Map<String, Object> dispatchChannels(@PathVariable Long id) {
        requireSuperAdmin();
        Map<String, Object> client = registryService.getClientById(id);
        return registryService.dispatchLicenseChannels(client);
    }

    @PostMapping("/clients/{id}/dispatch-email")
    public Map<String, Object> dispatchEmail(@PathVariable Long id) {
        requireSuperAdmin();
        Map<String, Object> client = registryService.getClientById(id);
        return registryService.dispatchEmailChannel(client);
    }

    @PostMapping("/clients/{id}/dispatch-whatsapp")
    public Map<String, Object> dispatchWhatsApp(@PathVariable Long id) {
        requireSuperAdmin();
        Map<String, Object> client = registryService.getClientById(id);
        return registryService.dispatchWhatsAppChannel(client);
    }

    @PostMapping("/leads/{id}/reject")
    public Map<String, Object> rejectLead(@PathVariable Long id) {
        requireSuperAdmin();
        return registryService.rejectLead(id);
    }

    @DeleteMapping("/leads/{id}")
    public Map<String, Object> deleteLead(@PathVariable Long id) {
        requireSuperAdmin();
        return registryService.deleteLead(id);
    }
}
