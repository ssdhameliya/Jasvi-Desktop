-- ============================================================================
-- DSE ERP 11.0.83: Enterprise Email Outbox & Auto-Retry, Per-Bank GL Accounts,
-- GST Charge Split, Subscription Expiry Watchdog, UTR Lead Proof & Monthly Telemetry
-- ============================================================================

-- 1. Persistent Email Outbox & Retry Queue (CC/BCC, audit traceability & exponential backoff)
CREATE TABLE IF NOT EXISTS email_outbox (
    id BIGSERIAL PRIMARY KEY,
    channel VARCHAR(32) NOT NULL DEFAULT 'EMAIL',
    recipient_to TEXT NOT NULL,
    recipient_cc TEXT,
    recipient_bcc TEXT,
    subject TEXT,
    body TEXT,
    is_html BOOLEAN NOT NULL DEFAULT TRUE,
    sender_email VARCHAR(255),
    sender_name VARCHAR(255),
    attachment_name VARCHAR(255),
    attachment_b64 TEXT,
    signature_name VARCHAR(255),
    signature_b64 TEXT,
    entity_type VARCHAR(64),
    entity_id BIGINT,
    reference_no VARCHAR(120),
    template_key VARCHAR(80),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    next_retry_at TIMESTAMP WITH TIME ZONE,
    last_error TEXT,
    created_by VARCHAR(160),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_email_outbox_retry
    ON email_outbox (status, next_retry_at);

CREATE INDEX IF NOT EXISTS idx_email_outbox_created
    ON email_outbox (created_at DESC);

-- 2. Per-Bank Account GL Asset Mapping & Bank Charge GST ITC Columns
CREATE TABLE IF NOT EXISTS bank_gl_account_map (
    id BIGSERIAL PRIMARY KEY,
    bank_account_key VARCHAR(120) NOT NULL UNIQUE,
    bank_name VARCHAR(255),
    account_number VARCHAR(120),
    gl_account_code VARCHAR(30) NOT NULL,
    gl_account_id BIGINT,
    updated_by VARCHAR(160),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE bank_statement_import
    ADD COLUMN IF NOT EXISTS gl_account_code VARCHAR(30);

ALTER TABLE finance_register
    ADD COLUMN IF NOT EXISTS gst_rate DOUBLE PRECISION DEFAULT 0,
    ADD COLUMN IF NOT EXISTS gst_amount DOUBLE PRECISION DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cgst_amount DOUBLE PRECISION DEFAULT 0,
    ADD COLUMN IF NOT EXISTS sgst_amount DOUBLE PRECISION DEFAULT 0,
    ADD COLUMN IF NOT EXISTS igst_amount DOUBLE PRECISION DEFAULT 0,
    ADD COLUMN IF NOT EXISTS vendor_gstin VARCHAR(32);

-- 3. Monthly Telemetry Rollup Archive (preserved prior to 30-day raw row purge)
CREATE TABLE IF NOT EXISTS client_telemetry_monthly_summary (
    id BIGSERIAL PRIMARY KEY,
    machine_id VARCHAR(64) NOT NULL,
    company_name VARCHAR(255),
    year_month VARCHAR(7) NOT NULL,
    total_work_seconds BIGINT NOT NULL DEFAULT 0,
    active_days INTEGER NOT NULL DEFAULT 0,
    distinct_users INTEGER NOT NULL DEFAULT 0,
    peak_concurrent_users INTEGER NOT NULL DEFAULT 0,
    session_count BIGINT NOT NULL DEFAULT 0,
    archived_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_client_telemetry_monthly UNIQUE (machine_id, year_month)
);

-- 4. Subscription Watchdog, Offline Workstation Alerts & Self-Service UTR Proof Columns
ALTER TABLE licensed_client_registry
    ADD COLUMN IF NOT EXISTS last_expiry_alert_stage VARCHAR(32),
    ADD COLUMN IF NOT EXISTS last_expiry_alert_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS last_offline_alert_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS seat_alert_sent_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE licensing_registration_lead
    ADD COLUMN IF NOT EXISTS utr_reference VARCHAR(120),
    ADD COLUMN IF NOT EXISTS payment_amount NUMERIC(18,2),
    ADD COLUMN IF NOT EXISTS payment_notes TEXT,
    ADD COLUMN IF NOT EXISTS requested_days INTEGER DEFAULT 365;

-- 5. Safe Default Automation Settings
INSERT INTO application_setting (setting_key, setting_value, updated_at)
VALUES
    ('email.outbox.enabled', 'true', CURRENT_TIMESTAMP),
    ('email.outbox.maxRetries', '3', CURRENT_TIMESTAMP),
    ('email.default.cc', '', CURRENT_TIMESTAMP),
    ('email.default.bcc', '', CURRENT_TIMESTAMP),
    ('licensing.expiry.reminder.enabled', 'true', CURRENT_TIMESTAMP),
    ('licensing.expiry.reminder.days', '30,15,7,1', CURRENT_TIMESTAMP),
    ('licensing.workstation.offlineAlertHours', '48', CURRENT_TIMESTAMP),
    ('banking.unreconciled.digest.enabled', 'true', CURRENT_TIMESTAMP)
ON CONFLICT (setting_key) DO NOTHING;
