-- V11_0_82__emergency_security_tokens.sql
-- Emergency Incident Recovery Engine: Single-use cryptographic action tokens for Admin lockout

CREATE TABLE IF NOT EXISTS emergency_security_action (
    id BIGSERIAL PRIMARY KEY,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    action_type VARCHAR(32) NOT NULL,
    ticket_id VARCHAR(32) NOT NULL,
    client_ip VARCHAR(64),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    used_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_emergency_security_token_hash ON emergency_security_action(token_hash);
CREATE INDEX IF NOT EXISTS idx_emergency_security_user_active ON emergency_security_action(user_id, used, expires_at);
