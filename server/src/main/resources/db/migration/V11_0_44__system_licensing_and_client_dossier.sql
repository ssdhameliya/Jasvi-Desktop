-- ============================================================================
-- DSE ERP 11.0.44: Cryptographic Signature Fix, Client Registry & GST Dossier
-- ============================================================================

-- 1. Fix Seeded App License with Verified Ed25519 Cryptographic Signature
UPDATE app_license
SET
    signature = 'RyOO+oAMMC7hPVC2wPaOAGH+JKL8Xmh8vI9m6cnw6wxRCOcE3BwBzRkY5jNEd4vxN/W98kCGVDx1iSMit0pSAg==',
    canonical_payload = 'JASVI-GOLD-628898|JASVI INDUSTRIES|F12DC2C0AADE15EB385BC67EE919B9CD663B49D0482D2C33EA3014B92245B6ED|GOLD|2026-11-03T22:00:28.852635900Z|2027-11-04T22:00:28.852635900Z|14|5|GST_INVOICE,EWAY_BILL,CLOUD_BACKUP',
    status = 'ACTIVE',
    last_verified_at = CURRENT_TIMESTAMP
WHERE license_key = 'JASVI-GOLD-628898';

-- 2. Master Client Companies Registry Table (Super Admin In-App Management)
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

-- Seed initial master company in registry
INSERT INTO licensed_client_registry (
    company_name, machine_id, contact_person, phone, email, tier, max_users, annual_fee,
    issued_at, valid_until, status, license_payload_enc, notes
) VALUES (
    'JASVI INDUSTRIES',
    'F12D-C2C0-AADE-15EB',
    'Shailesh Dhameliya',
    '9537544008',
    'shailesh.sdhameliya@gmail.com',
    'GOLD',
    5,
    3500.00,
    CURRENT_TIMESTAMP,
    '2027-11-04 22:00:28+00',
    'ACTIVE',
    '{"header":{"key":"JASVI-GOLD-628898","issuedAt":"2026-11-03T22:00:28.852635900Z","signature":"RyOO+oAMMC7hPVC2wPaOAGH+JKL8Xmh8vI9m6cnw6wxRCOcE3BwBzRkY5jNEd4vxN/W98kCGVDx1iSMit0pSAg=="},"payload":{"licenseKey":"JASVI-GOLD-628898","companyName":"JASVI INDUSTRIES","machineHash":"F12DC2C0AADE15EB385BC67EE919B9CD663B49D0482D2C33EA3014B92245B6ED","tier":"GOLD","validFrom":"2026-11-03T22:00:28.852635900Z","validUntil":"2027-11-04T22:00:28.852635900Z","gracePeriodDays":14,"maxUsers":5,"features":["GST_INVOICE","EWAY_BILL","CLOUD_BACKUP"]}}',
    'Master Anchor License'
) ON CONFLICT (machine_id) DO UPDATE SET
    status = 'ACTIVE',
    valid_until = EXCLUDED.valid_until,
    license_payload_enc = EXCLUDED.license_payload_enc;

-- 3. Enhance client_profiles with HSN Goods & Services classification data
ALTER TABLE client_profiles ADD COLUMN IF NOT EXISTS hsn_goods_services TEXT;
ALTER TABLE client_profiles ADD COLUMN IF NOT EXISTS effective_date VARCHAR(40);
ALTER TABLE client_profiles ADD COLUMN IF NOT EXISTS core_business_activity VARCHAR(120);

UPDATE client_profiles
SET
    hsn_goods_services = '8481:TAPS, COCKS, VALVES AND SIMILAR APPLIANCES FOR PIPES, BOILER SHELLS, TANKS|7318:SCREWS, BOLTS, NUTS, COACH-SCREWS, SCREW HOOKS, RIVETS, COTTERS|7307:TUBE OR PIPE FITTINGS (FOR EXAMPLE, COUPLINGS, ELBOWS, SLEEVES)|4009:TUBES, PIPES AND HOSES, OF VULCANISED RUBBER|9988:Manufacturing services on physical inputs (goods) owned by others',
    effective_date = '29/06/2021',
    core_business_activity = 'Manufacturer'
WHERE gstin = '24ANXPD3352N1ZK' OR client_name = 'JASVI INDUSTRIES';
