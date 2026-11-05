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
}
