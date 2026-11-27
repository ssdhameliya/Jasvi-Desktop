-- DSE ERP 11.0.83: Customer Credit Limit, Live Credit Health & Customer Price List / Quantity Slabs

ALTER TABLE party_master ADD COLUMN IF NOT EXISTS credit_limit NUMERIC(18,2);

CREATE TABLE IF NOT EXISTS customer_price_rule (
    id BIGSERIAL PRIMARY KEY,
    party_id INTEGER NOT NULL REFERENCES party_master(id) ON DELETE CASCADE,
    item_code VARCHAR(120) NOT NULL,
    min_qty NUMERIC(18,3) NOT NULL DEFAULT 1,
    special_rate NUMERIC(18,2) NOT NULL,
    discount_percent NUMERIC(7,2) NOT NULL DEFAULT 0,
    valid_from VARCHAR(20),
    valid_to VARCHAR(20),
    notes TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(160),
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(160),
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_customer_price_rule_lookup
    ON customer_price_rule (party_id, item_code, active, min_qty);
