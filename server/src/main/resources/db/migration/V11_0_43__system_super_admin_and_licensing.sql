-- v11.0.43: Enterprise Licensing, Hardware Fingerprint Binding, and System Super Admin Shield
-- 1. Create Application License Storage Table
CREATE TABLE IF NOT EXISTS app_license (
    id SERIAL PRIMARY KEY,
    license_key VARCHAR(64) NOT NULL UNIQUE,
    company_name VARCHAR(255) NOT NULL,
    machine_hash VARCHAR(128) NOT NULL,
    tier VARCHAR(32) NOT NULL DEFAULT 'SILVER',
    valid_from TIMESTAMP WITH TIME ZONE NOT NULL,
    valid_until TIMESTAMP WITH TIME ZONE NOT NULL,
    grace_period_days INT NOT NULL DEFAULT 14,
    max_users INT NOT NULL DEFAULT 1,
    features TEXT,
    signature TEXT NOT NULL,
    canonical_payload TEXT NOT NULL,
    last_verified_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_app_license_status ON app_license(status);
CREATE INDEX IF NOT EXISTS idx_app_license_key ON app_license(license_key);

-- 2. Register Protected SUPER_ADMIN Role in Master Data
DELETE FROM lookup_master WHERE UPPER(TRIM(lookup_type))='ROLE' AND UPPER(TRIM(lookup_code))='SUPER_ADMIN';
INSERT INTO lookup_master (lookup_type, lookup_code, lookup_value, description, display_order, is_active)
VALUES ('ROLE', 'SUPER_ADMIN', 'SUPER_ADMIN', 'Super Admin', 1, 1);

-- 3. Seed Master Developer Account (Password: JasviMaster@2026#)
INSERT INTO users (username, password, full_name, role, access_level, active, approval_status, mfa_enabled)
VALUES (
    'superadmin',
    '$2a$10$0JYgrMB4SbTzrSV3xg4t2eWXy9FAeL.Z1C0cJ2NvJj6QERCxGQXf2',
    'Jasvi Platform Creator',
    'SUPER_ADMIN',
    'SYSTEM_ROOT',
    1,
    'APPROVED',
    0
)
ON CONFLICT (username) DO UPDATE SET
    role = 'SUPER_ADMIN',
    access_level = 'SYSTEM_ROOT',
    active = 1,
    approval_status = 'APPROVED',
    mfa_enabled = 0,
    totp_secret_enc = NULL;

-- 4. Seed Pre-Activated Gold License for Jasvi Industries
INSERT INTO app_license (
    license_key, company_name, machine_hash, tier, valid_from, valid_until,
    grace_period_days, max_users, features, signature, canonical_payload, last_verified_at, status
) VALUES (
    'JASVI-GOLD-628898',
    'JASVI INDUSTRIES',
    'F12DC2C0AADE15EB385BC67EE919B9CD663B49D0482D2C33EA3014B92245B6ED',
    'GOLD',
    '2026-11-03 22:00:28+00',
    '2027-11-04 22:00:28+00',
    14,
    5,
    'GST_INVOICE,EWAY_BILL,CLOUD_BACKUP',
    'XpCCI8nCbCMY30K+oHssPN4lplIyNwRwO3X2fbOyCaZJr7GeA20sXyt4d3NSCa9fMbiUV8RwvEjDLb70kI6fAA==',
    'JASVI-GOLD-628898|JASVI INDUSTRIES|F12DC2C0AADE15EB385BC67EE919B9CD663B49D0482D2C33EA3014B92245B6ED|GOLD|2026-11-03T22:00:28.852635900Z|2027-11-04T22:00:28.852635900Z|14|5|GST_INVOICE,EWAY_BILL,CLOUD_BACKUP',
    CURRENT_TIMESTAMP,
    'ACTIVE'
)
ON CONFLICT (license_key) DO UPDATE SET
    status = 'ACTIVE',
    valid_until = EXCLUDED.valid_until,
    last_verified_at = CURRENT_TIMESTAMP;

-- 5. Seed Verified Company Profile in Client Registry (Authoritative Live Data)
INSERT INTO client_profiles (
    client_name, tally_company_name, gstin, trade_name, legal_name, address,
    gst_status, taxpayer_type, registration_date, constitution_of_business,
    state_jurisdiction, center_jurisdiction, nature_of_business
) VALUES (
    'JASVI INDUSTRIES',
    'JASVI INDUSTRIES',
    '24ANXPD3352N1ZK',
    'JASVI INDUSTRIES',
    'BIPIN SHAMBHUBHAI DHAMELIYA',
    'GF, SHED NO 6, HARI KRISHNA ESTATE, KATHWADA SINGARVA ROAD, KATHWADA NIKOL, OPP KRISHNA IND ESTATE, Ahmedabad, Ahmedabad, Gujarat, 382350',
    'ACTIVE',
    'Regular',
    '29/06/2021',
    'Proprietorship',
    'Division 2, Range 6, Unit Ghatak 20 (Ahmedabad), Gujarat',
    'Range I, Division V - ODHAV, Commissionerate AHMEDABAD SOUTH, Gujarat',
    'Manufacturer, Retail Business, Works Contract, Supplier of Services, Factory / Manufacturing'
) ON CONFLICT (client_name) DO UPDATE SET
    gstin = EXCLUDED.gstin,
    trade_name = EXCLUDED.trade_name,
    legal_name = EXCLUDED.legal_name,
    address = EXCLUDED.address,
    gst_status = EXCLUDED.gst_status,
    taxpayer_type = EXCLUDED.taxpayer_type,
    registration_date = EXCLUDED.registration_date,
    constitution_of_business = EXCLUDED.constitution_of_business,
    state_jurisdiction = EXCLUDED.state_jurisdiction,
    center_jurisdiction = EXCLUDED.center_jurisdiction,
    nature_of_business = EXCLUDED.nature_of_business;
