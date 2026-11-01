-- DSE ERP 11.0.29: User Phone Authentication & Access
-- Adds phone number column to users and registration_request.
-- Enforces partial unique constraint so users with a phone cannot share numbers,
-- while allowing legacy accounts with NULL to remain valid.

ALTER TABLE users ADD COLUMN IF NOT EXISTS phone VARCHAR(30);
ALTER TABLE registration_request ADD COLUMN IF NOT EXISTS phone VARCHAR(30);

CREATE UNIQUE INDEX IF NOT EXISTS ux_users_phone ON users(phone) 
WHERE phone IS NOT NULL AND BTRIM(phone) <> '';

CREATE UNIQUE INDEX IF NOT EXISTS ux_registration_request_phone_open ON registration_request(phone) 
WHERE status IN ('MFA_ENROLLMENT_PENDING','PENDING_ADMIN_APPROVAL') 
  AND phone IS NOT NULL AND BTRIM(phone) <> '';
