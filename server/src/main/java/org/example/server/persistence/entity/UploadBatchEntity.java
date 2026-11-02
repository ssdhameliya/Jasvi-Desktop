package org.example.server.persistence.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "upload_batches")
public class UploadBatchEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_profile_id")
    private Long clientProfileId;

    @Column(name = "batch_reference", nullable = false, unique = true)
    private String batchReference;

    @Column(name = "total_files", nullable = false)
    private Integer totalFiles = 0;

    @Column(name = "processed_count", nullable = false)
    private Integer processedCount = 0;

    @Column(name = "api_calls_consumed", nullable = false)
    private Integer apiCallsConsumed = 0;

    @Column(name = "status", nullable = false)
    private String status = "PENDING"; // PENDING, PROCESSING, REVIEW_NEEDED, COMPLETED

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getClientProfileId() { return clientProfileId; }
    public void setClientProfileId(Long clientProfileId) { this.clientProfileId = clientProfileId; }
    public String getBatchReference() { return batchReference; }
    public void setBatchReference(String batchReference) { this.batchReference = batchReference; }
    public Integer getTotalFiles() { return totalFiles; }
    public void setTotalFiles(Integer totalFiles) { this.totalFiles = totalFiles; }
    public Integer getProcessedCount() { return processedCount; }
    public void setProcessedCount(Integer processedCount) { this.processedCount = processedCount; }
    public Integer getApiCallsConsumed() { return apiCallsConsumed; }
    public void setApiCallsConsumed(Integer apiCallsConsumed) { this.apiCallsConsumed = apiCallsConsumed; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
