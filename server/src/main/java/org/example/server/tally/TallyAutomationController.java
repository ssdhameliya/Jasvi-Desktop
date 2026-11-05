package org.example.server.tally;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tally-automation")
public class TallyAutomationController {

    private final TallyAutomationService service;

    public TallyAutomationController(TallyAutomationService service) {
        this.service = service;
    }

    @GetMapping("/profiles")
    public List<TallyAutomationDtos.ClientProfileDto> listProfiles() {
        return service.listProfiles();
    }

    @PostMapping("/profiles")
    public TallyAutomationDtos.ClientProfileDto saveProfile(@RequestBody TallyAutomationDtos.ClientProfileDto dto) {
        return service.saveProfile(dto);
    }

    @DeleteMapping("/profiles/{id}")
    public void deleteProfile(@PathVariable Long id) {
        service.deleteProfile(id);
    }

    @GetMapping("/clients/directory")
    public List<TallyAutomationDtos.ClientDirectoryRowDto> listClientsDirectory() {
        return service.listClientsDirectory();
    }

    @PostMapping("/clients/verify-gstin")
    public TallyAutomationDtos.GstPortalProfileDto verifyGstin(@RequestBody TallyAutomationDtos.GstinVerificationRequest request) {
        return service.verifyGstin(request != null ? request.gstin() : "");
    }

    @GetMapping("/clients/{clientProfileId}/templates")
    public List<TallyAutomationDtos.TemplateVersionDto> listTemplates(@PathVariable Long clientProfileId) {
        return service.listTemplates(clientProfileId);
    }

    @GetMapping("/templates/by-gstin/{gstin}")
    public List<TallyAutomationDtos.TemplateVersionDto> listTemplatesByGstin(@PathVariable String gstin) {
        return service.listTemplatesByGstin(gstin);
    }

    @PostMapping("/templates")
    public TallyAutomationDtos.TemplateVersionDto saveTemplate(@RequestBody TallyAutomationDtos.TemplateVersionDto dto) {
        return service.saveTemplate(dto);
    }

    @PostMapping("/templates/{id}/lock")
    public TallyAutomationDtos.TemplateVersionDto lockTemplate(@PathVariable Long id) {
        return service.lockTemplate(id);
    }

    @DeleteMapping("/templates/{id}")
    public void deleteTemplate(@PathVariable Long id) {
        service.deleteTemplate(id);
    }

    @PostMapping("/batches")
    public TallyAutomationDtos.UploadBatchDto createBatch(
            @RequestParam(required = false) Long clientProfileId,
            @RequestParam(required = false) String reference,
            @RequestParam(defaultValue = "1") int totalFiles,
            @RequestParam(required = false) String user) {
        return service.createBatch(clientProfileId, reference, totalFiles, user);
    }

    @GetMapping("/batches")
    public Page<TallyAutomationDtos.UploadBatchDto> listBatches(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listBatches(PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size))));
    }

    @GetMapping("/batches/{id}")
    public TallyAutomationDtos.UploadBatchDto getBatch(@PathVariable Long id) {
        return service.getBatch(id);
    }

    @GetMapping("/batches/{id}/bills")
    public List<TallyAutomationDtos.BillRecordDto> listBills(@PathVariable Long id) {
        return service.listBills(id);
    }

    @GetMapping("/batches/{id}/metrics")
    public TallyAutomationDtos.BatchSummaryMetrics metrics(@PathVariable Long id) {
        return service.computeMetrics(id);
    }

    @PostMapping("/bills")
    public TallyAutomationDtos.BillRecordDto saveBill(@RequestBody TallyAutomationDtos.BillRecordDto dto) {
        return service.saveBill(dto);
    }

    @DeleteMapping("/bills/{id}")
    public void deleteBill(@PathVariable Long id) {
        service.deleteBill(id);
    }

    @PostMapping("/export")
    public TallyAutomationDtos.TallyExportResponse exportToTally(@RequestBody TallyAutomationDtos.TallyExportRequest request) {
        return service.exportToTally(request);
    }
}
