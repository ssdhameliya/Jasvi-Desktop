package org.example.server.documents;

import org.example.server.persistence.entity.CompanyTemplateMappingEntity;
import org.example.server.persistence.repository.CompanyTemplateMappingRepository;
import org.example.server.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class CompanyTemplateLinkingService {

    public record TemplateOption(String code, String name, String operationType, String description) {}

    public static final List<TemplateOption> CATALOG = List.of(
            // SALES PDF
            new TemplateOption("Jasvi_Sale", "Jasvi Flagship GST Tax Invoice (Core)", "SALES", "Flagship standard GST tax invoice with full statutory tax schedule and company banking."),
            new TemplateOption("Standard_Sale_Modern", "Contemporary Corporate Layout", "SALES", "Full-width modern header banner with clean minimalist grid typography."),
            new TemplateOption("Standard_Sale_Compact", "High-Density Spare Parts Grid", "SALES", "Optimized two-column dense layout for hardware, traders, and high line counts."),
            new TemplateOption("Standard_Sale_Thermal", "3-Inch POS Counter Receipt", "SALES", "80mm compact slip format for retail counters and cash transactions."),
            new TemplateOption("Standard_Sale_Export", "Export Commercial Invoice (LUT/Bond)", "SALES", "Export compliant invoice with LUT/Bond declaration, port, and foreign currency fields."),

            // PURCHASE PDF
            new TemplateOption("Jasvi_Purchase", "Jasvi Standard Purchase Order (Core)", "PURCHASE", "Official procurement purchase order with delivery instructions and schedule."),
            new TemplateOption("Standard_Purchase_Compact", "High-Density Goods Receipt PO", "PURCHASE", "Compact purchase bill optimized for raw material and inventory intake."),

            // QUOTATION PDF
            new TemplateOption("Jasvi_Quotation", "Jasvi Machinery Commercial Proposal (Core)", "QUOTATION", "Detailed machinery quotation with warranty clauses, validity, and commercial terms."),
            new TemplateOption("Standard_Quotation_Estimate", "Quick Industrial Price Estimate", "QUOTATION", "Concise commercial estimate format with standard delivery terms."),

            // RETURNS PDF
            new TemplateOption("Jasvi_Return", "Jasvi GST Credit & Debit Note (Core)", "RETURNS", "Statutory GST credit/debit adjustment note cross-referenced to original invoice.")
    );

    private final CompanyTemplateMappingRepository repository;

    public CompanyTemplateLinkingService(CompanyTemplateMappingRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<TemplateOption> getAvailableCatalog(String operationType) {
        if (operationType == null || operationType.isBlank()) {
            return CATALOG;
        }
        String op = operationType.trim().toUpperCase(Locale.ROOT);
        return CATALOG.stream().filter(t -> t.operationType().equalsIgnoreCase(op)).toList();
    }

    @Transactional(readOnly = true)
    public List<CompanyTemplateMappingEntity> getMappingsForCompany(Long companyId) {
        CurrentUser.require();
        Long cid = (companyId == null || companyId <= 0) ? 1L : companyId;
        List<CompanyTemplateMappingEntity> existing = repository.findByCompanyId(cid);
        if (existing.isEmpty() && cid == 1L) {
            // Seed defaults for Company 1 if not yet created
            return initDefaultCompanyOne();
        }
        return existing;
    }

    @Transactional
    public CompanyTemplateMappingEntity saveMapping(Long companyId, String operationType, String templateCode, String templateName) {
        var user = CurrentUser.require();
        if (!("ADMIN".equalsIgnoreCase(user.role()) || "SUPER_ADMIN".equalsIgnoreCase(user.role()))) {
            throw new SecurityException("Template linking requires administrator privileges");
        }
        Long cid = (companyId == null || companyId <= 0) ? 1L : companyId;
        String op = operationType == null ? "SALES" : operationType.trim().toUpperCase(Locale.ROOT);
        String code = templateCode == null ? "Jasvi_Sale" : templateCode.trim();
        String name = templateName == null ? code : templateName.trim();
        String username = CurrentUser.require().username();

        CompanyTemplateMappingEntity entity = repository.findByCompanyIdAndOperationType(cid, op)
                .orElse(new CompanyTemplateMappingEntity(cid, op, code, name, username));

        entity.setTemplateCode(code);
        entity.setTemplateName(name);
        entity.setUpdatedBy(username);
        entity.setUpdatedAt(OffsetDateTime.now());
        return repository.save(entity);
    }

    @Transactional
    public List<CompanyTemplateMappingEntity> saveBatchMappings(Long companyId, Map<String, String> mappings) {
        var user = CurrentUser.require();
        if (!("ADMIN".equalsIgnoreCase(user.role()) || "SUPER_ADMIN".equalsIgnoreCase(user.role()))) {
            throw new SecurityException("Template linking requires administrator privileges");
        }
        if (mappings == null || mappings.isEmpty()) return List.of();
        List<CompanyTemplateMappingEntity> saved = new ArrayList<>();
        for (var entry : mappings.entrySet()) {
            String op = entry.getKey();
            String code = entry.getValue();
            String name = CATALOG.stream()
                    .filter(c -> c.code().equalsIgnoreCase(code))
                    .map(TemplateOption::name)
                    .findFirst()
                    .orElse(code);
            saved.add(saveMapping(companyId, op, code, name));
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public String resolveTemplateCode(Long companyId, String operationType) {
        Long cid = (companyId == null || companyId <= 0) ? 1L : companyId;
        String op = operationType == null ? "SALES" : operationType.trim().toUpperCase(Locale.ROOT);
        // Direct match
        var direct = repository.findByCompanyIdAndOperationType(cid, op);
        if (direct.isPresent()) return direct.get().getTemplateCode();

        // If requesting base operation (e.g. SALES), check if SALES_PDF exists
        if (!op.endsWith("_PDF") && !op.endsWith("_EXCEL")) {
            var pdfVariant = repository.findByCompanyIdAndOperationType(cid, op + "_PDF");
            if (pdfVariant.isPresent()) return pdfVariant.get().getTemplateCode();
        } else if (op.endsWith("_PDF")) {
            // If requesting SALES_PDF, fallback to base SALES
            String base = op.substring(0, op.length() - 4);
            var baseVariant = repository.findByCompanyIdAndOperationType(cid, base);
            if (baseVariant.isPresent()) return baseVariant.get().getTemplateCode();
        }

        return defaultFor(op);
    }

    public static String defaultFor(String operationType) {
        if (operationType == null) return "Jasvi_Sale";
        String op = operationType.toUpperCase(Locale.ROOT);
        return switch (op) {
            case "PURCHASE", "PURCHASE_PDF" -> "Jasvi_Purchase";
            case "PURCHASE_EXCEL" -> "BuiltIn_Purchase_Excel";
            case "QUOTATION", "QUOTATION_PDF" -> "Jasvi_Quotation";
            case "QUOTATION_EXCEL" -> "BuiltIn_Quotation_Excel";
            case "RETURNS", "RETURNS_PDF" -> "Jasvi_Return";
            case "RETURNS_EXCEL" -> "New Purchase Return Excel";
            case "SALES_EXCEL" -> "Sale Invoice 1";
            default -> "Jasvi_Sale";
        };
    }

    private List<CompanyTemplateMappingEntity> initDefaultCompanyOne() {
        List<CompanyTemplateMappingEntity> defaults = List.of(
                new CompanyTemplateMappingEntity(1L, "SALES", "Jasvi_Sale", "Jasvi Flagship GST Tax Invoice (Core)", "superadmin"),
                new CompanyTemplateMappingEntity(1L, "SALES_PDF", "Jasvi_Sale", "Jasvi Flagship GST Tax Invoice (Core)", "superadmin"),
                new CompanyTemplateMappingEntity(1L, "SALES_EXCEL", "Sale Invoice 1", "Sale Invoice 1 (Excel Studio)", "superadmin"),
                new CompanyTemplateMappingEntity(1L, "PURCHASE", "Jasvi_Purchase", "Jasvi Standard Purchase Order (Core)", "superadmin"),
                new CompanyTemplateMappingEntity(1L, "PURCHASE_PDF", "Jasvi_Purchase", "Jasvi Standard Purchase Order (Core)", "superadmin"),
                new CompanyTemplateMappingEntity(1L, "PURCHASE_EXCEL", "BuiltIn_Purchase_Excel", "Standard Procurement PO", "superadmin"),
                new CompanyTemplateMappingEntity(1L, "QUOTATION", "Jasvi_Quotation", "Jasvi Machinery Commercial Proposal (Core)", "superadmin"),
                new CompanyTemplateMappingEntity(1L, "QUOTATION_PDF", "Jasvi_Quotation", "Jasvi Machinery Commercial Proposal (Core)", "superadmin"),
                new CompanyTemplateMappingEntity(1L, "QUOTATION_EXCEL", "BuiltIn_Quotation_Excel", "Quick Commercial Proposal", "superadmin"),
                new CompanyTemplateMappingEntity(1L, "RETURNS", "Jasvi_Return", "Jasvi GST Credit & Debit Note (Core)", "superadmin"),
                new CompanyTemplateMappingEntity(1L, "RETURNS_PDF", "Jasvi_Return", "Jasvi GST Credit & Debit Note (Core)", "superadmin"),
                new CompanyTemplateMappingEntity(1L, "RETURNS_EXCEL", "New Purchase Return Excel", "New Purchase Return Excel (Excel Studio)", "superadmin")
        );
        return repository.saveAll(defaults);
    }
}
