-- DSE ERP 11.0.50.1: Offline-to-Cloud PostgreSQL Sync Engine Schema
-- Tracks offline transactions, outbox queue for synchronization, and audit history.

CREATE TABLE IF NOT EXISTS offline_sync_outbox (
    id BIGSERIAL PRIMARY KEY,
    sync_uuid VARCHAR(64) NOT NULL UNIQUE,
    entity_type VARCHAR(50) NOT NULL,
    action VARCHAR(20) NOT NULL DEFAULT 'CREATE',
    entity_identifier VARCHAR(100),
    payload TEXT NOT NULL,
    sync_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    origin_workstation VARCHAR(100),
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    synced_at TIMESTAMP WITHOUT TIME ZONE,
    error_message TEXT,
    retry_count INTEGER DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_sync_outbox_status ON offline_sync_outbox(sync_status);
CREATE INDEX IF NOT EXISTS idx_sync_outbox_uuid ON offline_sync_outbox(sync_uuid);

CREATE TABLE IF NOT EXISTS offline_sync_audit (
    id BIGSERIAL PRIMARY KEY,
    sync_batch_id VARCHAR(64) NOT NULL,
    direction VARCHAR(10) NOT NULL DEFAULT 'PUSH',
    records_count INTEGER NOT NULL DEFAULT 0,
    success_count INTEGER NOT NULL DEFAULT 0,
    failure_count INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    origin_workstation VARCHAR(100),
    executed_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sync_audit_batch ON offline_sync_audit(sync_batch_id);

-- Add optional sync metadata to sales_header and purchase_header if not already present
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'sales_header' AND column_name = 'sync_status') THEN
        ALTER TABLE sales_header ADD COLUMN sync_status VARCHAR(20) DEFAULT 'SYNCED';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'sales_header' AND column_name = 'sync_uuid') THEN
        ALTER TABLE sales_header ADD COLUMN sync_uuid VARCHAR(64);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'purchase_header' AND column_name = 'sync_status') THEN
        ALTER TABLE purchase_header ADD COLUMN sync_status VARCHAR(20) DEFAULT 'SYNCED';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'purchase_header' AND column_name = 'sync_uuid') THEN
        ALTER TABLE purchase_header ADD COLUMN sync_uuid VARCHAR(64);
    END IF;
END $$;
