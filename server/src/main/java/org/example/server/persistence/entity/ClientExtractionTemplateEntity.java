package org.example.server.persistence.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "client_extraction_templates", uniqueConstraints = {
    @UniqueConstraint(name = "uq_client_template_version", columnNames = {"client_profile_id", "version_code"})
})
public class ClientExtractionTemplateEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_profile_id", nullable = false)
    private Long clientProfileId;

    @Column(name = "template_name", nullable = false)
    private String templateName;

    @Column(name = "version_code", nullable = false)
    private String versionCode = "v1";

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "is_locked", nullable = false)
    private Boolean isLocked = false;

    @Column(name = "vendor_gstin")
    private String vendorGstin;

    @Column(name = "matching_keywords", columnDefinition = "TEXT")
    private String matchingKeywords;

    @Column(name = "header_fields_json", nullable = false, columnDefinition = "TEXT")
    private String headerFieldsJson = "[]";

    @Column(name = "item_columns_json", nullable = false, columnDefinition = "TEXT")
    private String itemColumnsJson = "[]";

    @Column(name = "sample_file_path")
    private String sampleFilePath;

    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getClientProfileId() { return clientProfileId; }
    public void setClientProfileId(Long clientProfileId) { this.clientProfileId = clientProfileId; }
    public String getTemplateName() { return templateName; }
    public void setTemplateName(String templateName) { this.templateName = templateName; }
    public String getVersionCode() { return versionCode; }
    public void setVersionCode(String versionCode) { this.versionCode = versionCode; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public Boolean getIsLocked() { return isLocked; }
    public void setIsLocked(Boolean isLocked) { this.isLocked = isLocked; }
    public String getVendorGstin() { return vendorGstin; }
    public void setVendorGstin(String vendorGstin) { this.vendorGstin = vendorGstin; }
    public String getMatchingKeywords() { return matchingKeywords; }
    public void setMatchingKeywords(String matchingKeywords) { this.matchingKeywords = matchingKeywords; }
    public String getHeaderFieldsJson() { return headerFieldsJson; }
    public void setHeaderFieldsJson(String headerFieldsJson) { this.headerFieldsJson = headerFieldsJson; }
    public String getItemColumnsJson() { return itemColumnsJson; }
    public void setItemColumnsJson(String itemColumnsJson) { this.itemColumnsJson = itemColumnsJson; }
    public String getSampleFilePath() { return sampleFilePath; }
    public void setSampleFilePath(String sampleFilePath) { this.sampleFilePath = sampleFilePath; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
