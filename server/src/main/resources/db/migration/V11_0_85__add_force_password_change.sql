-- V11_0_85__add_force_password_change.sql
-- Enterprise Emergency Recovery: Mandatory first-login password change flag for temporary credentials

ALTER TABLE users ADD COLUMN IF NOT EXISTS force_password_change INT NOT NULL DEFAULT 0;
