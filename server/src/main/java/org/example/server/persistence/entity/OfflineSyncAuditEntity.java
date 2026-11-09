package org.example.server.persistence.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "offline_sync_audit")
public class OfflineSyncAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sync_batch_id", nullable = false, length = 64)
    private String syncBatchId;

    @Column(name = "direction", nullable = false, length = 10)
    private String direction = "PUSH";

    @Column(name = "records_count", nullable = false)
    private Integer recordsCount = 0;

    @Column(name = "success_count", nullable = false)
    private Integer successCount = 0;

    @Column(name = "failure_count", nullable = false)
    private Integer failureCount = 0;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "COMPLETED";

    @Column(name = "origin_workstation", length = 100)
    private String originWorkstation;

    @Column(name = "executed_at")
    private LocalDateTime executedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSyncBatchId() { return syncBatchId; }
    public void setSyncBatchId(String syncBatchId) { this.syncBatchId = syncBatchId; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public Integer getRecordsCount() { return recordsCount; }
    public void setRecordsCount(Integer recordsCount) { this.recordsCount = recordsCount; }

    public Integer getSuccessCount() { return successCount; }
    public void setSuccessCount(Integer successCount) { this.successCount = successCount; }

    public Integer getFailureCount() { return failureCount; }
    public void setFailureCount(Integer failureCount) { this.failureCount = failureCount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getOriginWorkstation() { return originWorkstation; }
    public void setOriginWorkstation(String originWorkstation) { this.originWorkstation = originWorkstation; }

    public LocalDateTime getExecutedAt() { return executedAt; }
    public void setExecutedAt(LocalDateTime executedAt) { this.executedAt = executedAt; }
}
