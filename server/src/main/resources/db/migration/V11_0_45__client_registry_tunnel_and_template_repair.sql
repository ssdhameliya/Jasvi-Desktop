-- ============================================================================
-- DSE ERP 11.0.45: Client Registry, Remote Access, Hardware Repair & Templates
-- ============================================================================

-- 1. Ensure Master Client Companies Registry Table Exists
CREATE TABLE IF NOT EXISTS licensed_client_registry (
    id BIGSERIAL PRIMARY KEY,
    company_name VARCHAR(255) NOT NULL,
    machine_id VARCHAR(64) NOT NULL UNIQUE,
    contact_person VARCHAR(120),
    phone VARCHAR(40),
    email VARCHAR(160),
    tier VARCHAR(32) NOT NULL DEFAULT 'GOLD',
    max_users INTEGER NOT NULL DEFAULT 5,
    annual_fee NUMERIC(12,2) NOT NULL DEFAULT 3500.00,
    issued_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_until TIMESTAMP NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    license_payload_enc TEXT NOT NULL,
    notes TEXT,
    row_version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_licensed_client_status ON licensed_client_registry(status, valid_until);
CREATE INDEX IF NOT EXISTS idx_licensed_client_machine ON licensed_client_registry(machine_id);

-- 2. Repair Anchor License in app_license Table to guarantee ACTIVE status
UPDATE app_license
SET
    company_name = 'JASVI INDUSTRIES',
    machine_hash = 'F12DC2C0AADE15EB385BC67EE919B9CD663B49D0482D2C33EA3014B92245B6ED',
    tier = 'GOLD',
    status = 'ACTIVE',
    valid_until = '2027-11-04 22:00:28+00',
    signature = 'RyOO+oAMMC7hPVC2wPaOAGH+JKL8Xmh8vI9m6cnw6wxRCOcE3BwBzRkY5jNEd4vxN/W98kCGVDx1iSMit0pSAg==',
    canonical_payload = 'JASVI-GOLD-628898|JASVI INDUSTRIES|F12DC2C0AADE15EB385BC67EE919B9CD663B49D0482D2C33EA3014B92245B6ED|GOLD|2026-11-03T22:00:28.852635900Z|2027-11-04T22:00:28.852635900Z|14|5|GST_INVOICE,EWAY_BILL,CLOUD_BACKUP',
    last_verified_at = CURRENT_TIMESTAMP
WHERE license_key = 'JASVI-GOLD-628898';

-- 3. Delete any orphaned or mismatched temporary licenses
DELETE FROM app_license WHERE status = 'MACHINE_MISMATCH' AND license_key <> 'JASVI-GOLD-628898';
