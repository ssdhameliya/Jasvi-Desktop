-- v11.0.91: Bank Statement Smart Rules v2 (amount range, regex, channel, bank account scope, party FIFO, contra) & self-learning party UPI/VPA alias memory

ALTER TABLE bank_reconciliation_rules
    ADD COLUMN IF NOT EXISTS min_amount DOUBLE PRECISION;

ALTER TABLE bank_reconciliation_rules
    ADD COLUMN IF NOT EXISTS max_amount DOUBLE PRECISION;

ALTER TABLE bank_reconciliation_rules
    ADD COLUMN IF NOT EXISTS channel_filter VARCHAR(32);

ALTER TABLE bank_reconciliation_rules
    ADD COLUMN IF NOT EXISTS bank_account_filter VARCHAR(160);

ALTER TABLE bank_reconciliation_rules
    ADD COLUMN IF NOT EXISTS target_party_id INTEGER;

ALTER TABLE bank_reconciliation_rules
    ADD COLUMN IF NOT EXISTS target_contra_account VARCHAR(160);

CREATE TABLE IF NOT EXISTS party_bank_alias (
    id            BIGSERIAL PRIMARY KEY,
    party_id      INTEGER      NOT NULL REFERENCES party_master(id) ON DELETE CASCADE,
    alias_pattern VARCHAR(160) NOT NULL,
    alias_type    VARCHAR(32)  NOT NULL DEFAULT 'UPI_HANDLE',
    hit_count     INTEGER      NOT NULL DEFAULT 1,
    last_seen_at  VARCHAR(64),
    created_by    VARCHAR(120),
    created_at    VARCHAR(64)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_party_bank_alias_pattern
    ON party_bank_alias (LOWER(alias_pattern));

CREATE INDEX IF NOT EXISTS idx_party_bank_alias_party
    ON party_bank_alias (party_id);
