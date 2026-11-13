-- ============================================================================
-- DSE ERP 11.0.60: Client User Session Telemetry, Plan Tracking & Rogue 1-Click Revocation
-- ============================================================================

-- 1. Create client_user_telemetry table for multi-user work hours tracking
CREATE TABLE IF NOT EXISTS client_user_telemetry (
    id BIGSERIAL PRIMARY KEY,
    machine_id VARCHAR(64) NOT NULL,
    company_name VARCHAR(255),
    username VARCHAR(100) NOT NULL,
    role_code VARCHAR(50) DEFAULT 'USER',
    work_date DATE NOT NULL DEFAULT CURRENT_DATE,
    first_login_today TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    last_logout_today TIMESTAMP WITH TIME ZONE,
    last_heartbeat TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    total_seconds_today BIGINT NOT NULL DEFAULT 0,
    is_active_now BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_client_user_work_session UNIQUE (machine_id, username, work_date)
);

CREATE INDEX IF NOT EXISTS idx_client_user_telemetry_machine_date 
    ON client_user_telemetry(machine_id, work_date);

CREATE INDEX IF NOT EXISTS idx_client_user_telemetry_username 
    ON client_user_telemetry(username);

-- 2. Add purchase status, alert debounce and kick tracking to licensed_client_registry
ALTER TABLE licensed_client_registry
    ADD COLUMN IF NOT EXISTS is_purchased BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS last_alert_sent_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS kicked_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS kick_reason TEXT;

-- 3. Create license_revocation_token table for secure 1-click kick/kill links
CREATE TABLE IF NOT EXISTS license_revocation_token (
    id BIGSERIAL PRIMARY KEY,
    token VARCHAR(128) NOT NULL UNIQUE,
    machine_id VARCHAR(64) NOT NULL,
    company_name VARCHAR(255),
    client_ip VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at TIMESTAMP WITH TIME ZONE,
    used_by_ip VARCHAR(100)
);

CREATE INDEX IF NOT EXISTS idx_license_revocation_token 
    ON license_revocation_token(token, expires_at);

-- 4. Default commercial settings for Super Admin notification
INSERT INTO application_setting (setting_key, setting_value, updated_at)
VALUES 
    ('licensing.superadmin_whatsapp', '9537544008', CURRENT_TIMESTAMP),
    ('licensing.superadmin_email', 'superadmin@jasviindustries.in', CURRENT_TIMESTAMP)
ON CONFLICT (setting_key) DO NOTHING;
