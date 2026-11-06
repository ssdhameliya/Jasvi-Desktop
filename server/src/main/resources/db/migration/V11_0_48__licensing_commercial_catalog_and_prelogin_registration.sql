-- v11.0.48: Commercial Licensing Catalog, Dynamic Plans, and Pre-Login Device Registration

-- 1. Create Licensing Plans Table
CREATE TABLE IF NOT EXISTS licensing_plan (
    id SERIAL PRIMARY KEY,
    tier_code VARCHAR(32) NOT NULL UNIQUE,
    plan_name VARCHAR(64) NOT NULL,
    max_users INT NOT NULL DEFAULT 5,
    annual_fee NUMERIC(12,2) NOT NULL DEFAULT 3500.00,
    validity_days INT NOT NULL DEFAULT 365,
    features TEXT NOT NULL,
    display_order INT NOT NULL DEFAULT 1,
    is_active INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_licensing_plan_active ON licensing_plan(is_active);

-- Seed Default Plans
INSERT INTO licensing_plan (tier_code, plan_name, max_users, annual_fee, validity_days, features, display_order, is_active)
VALUES
    ('SILVER', 'Silver Starter', 1, 2500.00, 365, 'GST_INVOICE,PRINT_EXPORT', 1, 1),
    ('GOLD', 'Gold Standard', 5, 3500.00, 365, 'GST_INVOICE,EWAY_BILL,CLOUD_BACKUP,PRINT_EXPORT', 2, 1),
    ('PLATINUM', 'Platinum Enterprise', 20, 7500.00, 365, 'GST_INVOICE,EWAY_BILL,CLOUD_BACKUP,MULTI_BRANCH,PRIORITY_SUPPORT,TALLY_SYNC,PRINT_EXPORT', 3, 1)
ON CONFLICT (tier_code) DO UPDATE SET
    plan_name = EXCLUDED.plan_name,
    max_users = EXCLUDED.max_users,
    annual_fee = EXCLUDED.annual_fee,
    validity_days = EXCLUDED.validity_days,
    features = EXCLUDED.features,
    display_order = EXCLUDED.display_order,
    is_active = EXCLUDED.is_active;

-- 2. Seed Dynamic Commercial Settings
INSERT INTO application_setting (setting_key, setting_value)
VALUES
    ('licensing.support_phone', '9537544008'),
    ('licensing.upi_vpa', 'jasviindustries@icici'),
    ('licensing.payee_name', 'Jasvi Industries'),
    ('licensing.gst_rate', '18.00'),
    ('licensing.whatsapp_template_lead', 'Hello Jasvi Team, I am {CompanyName} ({ContactPerson}, {Phone}). Terminal ID: {MachineId}. Please activate our Jasvi ERP license.'),
    ('licensing.whatsapp_template_issued', 'Dear {CompanyName}, your Jasvi ERP {Tier} license is approved! Terminal ID: {MachineId}. Valid Until: {ValidUntil}. Thank you for choosing Jasvi ERP.')
ON CONFLICT (setting_key) DO NOTHING;

-- 3. Create Pre-Login Registration Leads Table
CREATE TABLE IF NOT EXISTS licensing_registration_lead (
    id SERIAL PRIMARY KEY,
    company_name VARCHAR(255) NOT NULL,
    machine_id VARCHAR(64) NOT NULL,
    contact_person VARCHAR(128),
    phone VARCHAR(32),
    email VARCHAR(128),
    requested_tier VARCHAR(32) DEFAULT 'GOLD',
    status VARCHAR(32) DEFAULT 'PENDING',
    notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_licensing_lead_status ON licensing_registration_lead(status);
CREATE INDEX IF NOT EXISTS idx_licensing_lead_machine ON licensing_registration_lead(machine_id);
