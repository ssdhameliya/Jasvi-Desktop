-- V11_0_49: Client Telemetry Heartbeat, Remote URL Monitoring, and Multi-Channel License Dispatch
-- Adds telemetry tracking columns and remote support URL to client registry and registration leads.

ALTER TABLE licensed_client_registry
    ADD COLUMN IF NOT EXISTS remote_url VARCHAR(512),
    ADD COLUMN IF NOT EXISTS last_heartbeat_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS client_version VARCHAR(32),
    ADD COLUMN IF NOT EXISTS active_users_count INTEGER DEFAULT 0;

ALTER TABLE licensing_registration_lead
    ADD COLUMN IF NOT EXISTS remote_url VARCHAR(512);

CREATE INDEX IF NOT EXISTS idx_licensed_client_heartbeat
    ON licensed_client_registry(last_heartbeat_at);
