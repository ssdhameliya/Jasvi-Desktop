package org.example.server.whatsapp;

import org.example.server.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/authority/whatsapp")
public class BusinessWhatsappController {

    private final BusinessWhatsappService service;

    public BusinessWhatsappController(BusinessWhatsappService service) {
        this.service = service;
    }

    private void requireAdmin() {
        if (!"ADMIN".equalsIgnoreCase(CurrentUser.require().role()) && !"SUPER_ADMIN".equalsIgnoreCase(CurrentUser.require().role())) {
            throw new SecurityException("Only administrators can manage WhatsApp configuration");
        }
    }

    @GetMapping("/settings")
    public WahaDtos.WahaSettingsDto getSettings() {
        requireAdmin();
        return service.getSettings();
    }

    @PutMapping("/settings")
    public WahaDtos.WahaSettingsDto saveSettings(@RequestBody WahaDtos.WahaSettingsDto dto) {
        requireAdmin();
        service.saveSettings(dto);
        return service.getSettings();
    }

    @PostMapping("/test")
    public WahaDtos.HealthResponse testConnection() {
        requireAdmin();
        return service.testConnection();
    }

    @PostMapping("/server/start-docker")
    public WahaDtos.HealthResponse startDocker() {
        requireAdmin();
        return service.startDockerEngine();
    }

    @GetMapping("/channels")
    public List<WahaDtos.ChannelDto> listChannels() {
        return service.listChannels();
    }

    @PostMapping("/channels")
    public WahaDtos.ChannelDto saveChannel(@RequestBody WahaDtos.ChannelSaveRequest req) {
        requireAdmin();
        return service.saveChannel(req);
    }

    @DeleteMapping("/channels/{id}")
    public Map<String, Object> deleteChannel(@PathVariable Long id) {
        requireAdmin();
        service.deleteChannel(id);
        return Map.of("success", true, "message", "Channel deleted successfully");
    }

    @PutMapping("/channels/{id}/default")
    public Map<String, Object> setDefaultChannel(@PathVariable Long id) {
        requireAdmin();
        service.setDefaultChannel(id);
        return Map.of("success", true, "message", "Channel marked as default");
    }

    @GetMapping("/routing")
    public WahaDtos.DepartmentRoutingDto getDepartmentRouting() {
        requireAdmin();
        return service.getDepartmentRouting();
    }

    @PutMapping("/routing")
    public WahaDtos.DepartmentRoutingDto saveDepartmentRouting(@RequestBody WahaDtos.DepartmentRoutingDto dto) {
        requireAdmin();
        service.saveDepartmentRouting(dto);
        return service.getDepartmentRouting();
    }

    @GetMapping("/qr")
    public WahaDtos.QrResponse getQrDefault() {
        return service.getQrCode("default");
    }

    @GetMapping("/qr/{session}")
    public WahaDtos.QrResponse getQrSession(@PathVariable String session) {
        return service.getQrCode(session);
    }

    @PostMapping("/send-document")
    public WahaDtos.SendResponse sendDocument(@RequestBody WahaDtos.DocumentSendRequest req) {
        CurrentUser.requirePermission("COMMUNICATION.CREATE", "Send WhatsApp document");
        return service.sendDocument(req);
    }

    @PostMapping("/send-text")
    public WahaDtos.SendResponse sendText(@RequestBody WahaDtos.TextSendRequest req) {
        CurrentUser.requirePermission("COMMUNICATION.CREATE", "Send WhatsApp text");
        return service.sendText(req);
    }
}
