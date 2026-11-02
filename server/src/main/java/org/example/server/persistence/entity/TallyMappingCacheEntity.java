package org.example.server.persistence.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "tally_mapping_cache", uniqueConstraints = {
    @UniqueConstraint(name = "uk_client_mapping", columnNames = {"client_profile_id", "mapping_type", "source_identifier"})
})
public class TallyMappingCacheEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_profile_id", nullable = false)
    private ClientProfileEntity clientProfile;

    @Column(name = "mapping_type", nullable = false, length = 30)
    private String mappingType; // 'VENDOR_TO_LEDGER', 'ITEM_TO_STOCK'

    @Column(name = "source_identifier", nullable = false, length = 255)
    private String sourceIdentifier;

    @Column(name = "tally_target_name", nullable = false, length = 255)
    private String tallyTargetName;

    @Column(name = "tally_secondary_name", length = 100)
    private String tallySecondaryName;

    @Column(name = "confidence_hits", nullable = false)
    private Integer confidenceHits = 1;

    @Column(name = "last_applied_at")
    private OffsetDateTime lastAppliedAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public ClientProfileEntity getClientProfile() { return clientProfile; }
    public void setClientProfile(ClientProfileEntity clientProfile) { this.clientProfile = clientProfile; }

    public String getMappingType() { return mappingType; }
    public void setMappingType(String mappingType) { this.mappingType = mappingType; }

    public String getSourceIdentifier() { return sourceIdentifier; }
    public void setSourceIdentifier(String sourceIdentifier) { this.sourceIdentifier = sourceIdentifier; }

    public String getTallyTargetName() { return tallyTargetName; }
    public void setTallyTargetName(String tallyTargetName) { this.tallyTargetName = tallyTargetName; }

    public String getTallySecondaryName() { return tallySecondaryName; }
    public void setTallySecondaryName(String tallySecondaryName) { this.tallySecondaryName = tallySecondaryName; }

    public Integer getConfidenceHits() { return confidenceHits; }
    public void setConfidenceHits(Integer confidenceHits) { this.confidenceHits = confidenceHits; }

    public OffsetDateTime getLastAppliedAt() { return lastAppliedAt; }
    public void setLastAppliedAt(OffsetDateTime lastAppliedAt) { this.lastAppliedAt = lastAppliedAt; }
}
