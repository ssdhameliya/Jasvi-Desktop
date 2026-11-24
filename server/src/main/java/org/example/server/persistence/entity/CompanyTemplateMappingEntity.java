package org.example.server.persistence.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "company_template_mapping", uniqueConstraints = {
        @UniqueConstraint(name = "uq_company_template_operation", columnNames = {"company_id", "operation_type"})
})
public class CompanyTemplateMappingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "operation_type", nullable = false, length = 32)
    private String operationType;

    @Column(name = "template_code", nullable = false, length = 64)
    private String templateCode;

    @Column(name = "template_name", nullable = false, length = 120)
    private String templateName;

    @Column(name = "updated_by", nullable = false, length = 64)
    private String updatedBy = "superadmin";

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public CompanyTemplateMappingEntity() {}

    public CompanyTemplateMappingEntity(Long companyId, String operationType, String templateCode, String templateName, String updatedBy) {
        this.companyId = companyId;
        this.operationType = operationType;
        this.templateCode = templateCode;
        this.templateName = templateName;
        this.updatedBy = updatedBy;
        this.updatedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getCompanyId() { return companyId; }
    public void setCompanyId(Long companyId) { this.companyId = companyId; }

    public String getOperationType() { return operationType; }
    public void setOperationType(String operationType) { this.operationType = operationType; }

    public String getTemplateCode() { return templateCode; }
    public void setTemplateCode(String templateCode) { this.templateCode = templateCode; }

    public String getTemplateName() { return templateName; }
    public void setTemplateName(String templateName) { this.templateName = templateName; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
