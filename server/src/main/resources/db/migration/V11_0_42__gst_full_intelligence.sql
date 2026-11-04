-- V11_0_42__gst_full_intelligence.sql
-- Store comprehensive live GST portal taxpayer intelligence and filing status history

ALTER TABLE client_profiles
    ADD COLUMN IF NOT EXISTS registration_date VARCHAR(50),
    ADD COLUMN IF NOT EXISTS constitution_of_business VARCHAR(150),
    ADD COLUMN IF NOT EXISTS state_jurisdiction VARCHAR(200),
    ADD COLUMN IF NOT EXISTS center_jurisdiction VARCHAR(200),
    ADD COLUMN IF NOT EXISTS nature_of_business VARCHAR(250),
    ADD COLUMN IF NOT EXISTS filing_status_json TEXT DEFAULT '[]';
