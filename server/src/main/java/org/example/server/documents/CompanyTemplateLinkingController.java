package org.example.server.documents;

import org.example.server.persistence.entity.CompanyTemplateMappingEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class CompanyTemplateLinkingController {

    private final CompanyTemplateLinkingService service;

    public CompanyTemplateLinkingController(CompanyTemplateLinkingService service) {
        this.service = service;
    }

    @GetMapping("/templates/catalog")
    public ResponseEntity<List<CompanyTemplateLinkingService.TemplateOption>> getCatalog(
            @RequestParam(value = "operation", required = false) String operation) {
        return ResponseEntity.ok(service.getAvailableCatalog(operation));
    }

    @GetMapping("/companies/{companyId}/templates")
    public ResponseEntity<List<CompanyTemplateMappingEntity>> getCompanyMappings(
            @PathVariable("companyId") Long companyId) {
        return ResponseEntity.ok(service.getMappingsForCompany(companyId));
    }

    @PostMapping("/companies/{companyId}/templates")
    public ResponseEntity<List<CompanyTemplateMappingEntity>> saveCompanyMappings(
            @PathVariable("companyId") Long companyId,
            @RequestBody Map<String, String> mappings) {
        return ResponseEntity.ok(service.saveBatchMappings(companyId, mappings));
    }

    @GetMapping("/companies/{companyId}/templates/{operation}/resolve")
    public ResponseEntity<Map<String, String>> resolveTemplate(
            @PathVariable("companyId") Long companyId,
            @PathVariable("operation") String operation) {
        String code = service.resolveTemplateCode(companyId, operation);
        return ResponseEntity.ok(Map.of("companyId", String.valueOf(companyId), "operation", operation, "templateCode", code));
    }
}
