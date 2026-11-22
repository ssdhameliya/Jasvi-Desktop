-- V11_0_81__enhanced_login_security_and_password_history.sql
-- Enterprise Login Security: 3-attempt lockout, daily password reset limit, and 3-password history

ALTER TABLE users ADD COLUMN IF NOT EXISTS last_password_reset_utc VARCHAR(32);

CREATE TABLE IF NOT EXISTS user_password_history (
    id BIGSERIAL PRIMARY KEY,
    user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_user_password_history_user ON user_password_history(user_id, created_at DESC);
