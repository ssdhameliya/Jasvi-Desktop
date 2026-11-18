CREATE TABLE IF NOT EXISTS bank_reconciliation_rules (
    id                  BIGSERIAL PRIMARY KEY,
    rule_name           VARCHAR(160) NOT NULL,
    match_field         VARCHAR(32)  NOT NULL DEFAULT 'NARRATION',
    match_operator      VARCHAR(32)  NOT NULL DEFAULT 'CONTAINS',
    match_pattern       VARCHAR(255) NOT NULL,
    direction           VARCHAR(16)  NOT NULL DEFAULT 'BOTH',
    action_type         VARCHAR(32)  NOT NULL DEFAULT 'EXPENSE',
    target_category     VARCHAR(160),
    target_account_name VARCHAR(160),
    target_payment_mode VARCHAR(80),
    target_notes        TEXT,
    execution_mode      VARCHAR(24)  NOT NULL DEFAULT 'SUGGEST',
    priority            INTEGER      NOT NULL DEFAULT 100,
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    hit_count           INTEGER      NOT NULL DEFAULT 0,
    last_matched_at     VARCHAR(64),
    created_by          VARCHAR(120),
    created_at          VARCHAR(64),
    updated_at          VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_bank_recon_rules_active_priority
    ON bank_reconciliation_rules (active, priority, id);
