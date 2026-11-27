package org.example.server.persistence.entity;

import jakarta.persistence.*;
import org.example.server.util.BusinessClock;

@Entity
@Table(name = "bank_reconciliation_rules")
public class BankReconciliationRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_name", nullable = false, length = 160)
    private String ruleName;

    @Column(name = "match_field", nullable = false, length = 32)
    private String matchField = "NARRATION";

    @Column(name = "match_operator", nullable = false, length = 32)
    private String matchOperator = "CONTAINS";

    @Column(name = "match_pattern", nullable = false, length = 255)
    private String matchPattern;

    @Column(name = "direction", nullable = false, length = 16)
    private String direction = "BOTH";

    @Column(name = "action_type", nullable = false, length = 32)
    private String actionType = "EXPENSE";

    @Column(name = "target_category", length = 160)
    private String targetCategory;

    @Column(name = "target_account_name", length = 160)
    private String targetAccountName;

    @Column(name = "target_payment_mode", length = 80)
    private String targetPaymentMode;

    @Column(name = "target_notes", columnDefinition = "TEXT")
    private String targetNotes;

    @Column(name = "min_amount")
    private Double minAmount;

    @Column(name = "max_amount")
    private Double maxAmount;

    @Column(name = "channel_filter", length = 32)
    private String channelFilter;

    @Column(name = "bank_account_filter", length = 160)
    private String bankAccountFilter;

    @Column(name = "target_party_id")
    private Integer targetPartyId;

    @Column(name = "target_contra_account", length = 160)
    private String targetContraAccount;

    @Column(name = "execution_mode", nullable = false, length = 24)
    private String executionMode = "SUGGEST";

    @Column(name = "priority", nullable = false)
    private Integer priority = 100;

    @Column(name = "active", nullable = false)
    private Boolean active = Boolean.TRUE;

    @Column(name = "hit_count", nullable = false)
    private Integer hitCount = 0;

    @Column(name = "last_matched_at", length = 64)
    private String lastMatchedAt;

    @Column(name = "created_by", length = 120)
    private String createdBy;

    @Column(name = "created_at", length = 64)
    private String createdAt;

    @Column(name = "updated_at", length = 64)
    private String updatedAt;

    @PrePersist
    void onCreate() {
        String now = BusinessClock.nowUtcText();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (active == null) active = Boolean.TRUE;
        if (priority == null) priority = 100;
        if (hitCount == null) hitCount = 0;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = BusinessClock.nowUtcText();
    }

    public Long getId() { return id; }
    public String getRuleName() { return ruleName; }
    public void setRuleName(String v) { ruleName = v; }
    public String getMatchField() { return matchField; }
    public void setMatchField(String v) { matchField = v; }
    public String getMatchOperator() { return matchOperator; }
    public void setMatchOperator(String v) { matchOperator = v; }
    public String getMatchPattern() { return matchPattern; }
    public void setMatchPattern(String v) { matchPattern = v; }
    public String getDirection() { return direction; }
    public void setDirection(String v) { direction = v; }
    public String getActionType() { return actionType; }
    public void setActionType(String v) { actionType = v; }
    public String getTargetCategory() { return targetCategory; }
    public void setTargetCategory(String v) { targetCategory = v; }
    public String getTargetAccountName() { return targetAccountName; }
    public void setTargetAccountName(String v) { targetAccountName = v; }
    public String getTargetPaymentMode() { return targetPaymentMode; }
    public void setTargetPaymentMode(String v) { targetPaymentMode = v; }
    public String getTargetNotes() { return targetNotes; }
    public void setTargetNotes(String v) { targetNotes = v; }
    public Double getMinAmount() { return minAmount; }
    public void setMinAmount(Double v) { minAmount = v; }
    public Double getMaxAmount() { return maxAmount; }
    public void setMaxAmount(Double v) { maxAmount = v; }
    public String getChannelFilter() { return channelFilter; }
    public void setChannelFilter(String v) { channelFilter = v; }
    public String getBankAccountFilter() { return bankAccountFilter; }
    public void setBankAccountFilter(String v) { bankAccountFilter = v; }
    public Integer getTargetPartyId() { return targetPartyId; }
    public void setTargetPartyId(Integer v) { targetPartyId = v; }
    public String getTargetContraAccount() { return targetContraAccount; }
    public void setTargetContraAccount(String v) { targetContraAccount = v; }
    public String getExecutionMode() { return executionMode; }
    public void setExecutionMode(String v) { executionMode = v; }
    public Integer getPriority() { return priority == null ? 100 : priority; }
    public void setPriority(Integer v) { priority = v; }
    public boolean isActive() { return Boolean.TRUE.equals(active); }
    public void setActive(boolean v) { active = v; }
    public int getHitCount() { return hitCount == null ? 0 : hitCount; }
    public void setHitCount(int v) { hitCount = v; }
    public String getLastMatchedAt() { return lastMatchedAt; }
    public void setLastMatchedAt(String v) { lastMatchedAt = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { createdBy = v; }
    public String getCreatedAt() { return createdAt; }
    public String getUpdatedAt() { return updatedAt; }
}
