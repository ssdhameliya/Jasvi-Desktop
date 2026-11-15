-- ============================================================================
-- DSE ERP 11.0.67: Client Registry Cloud Workstation Unification & Company Aggregation
-- ============================================================================

-- 1. Correct company name typo and normalize company identity across registry & license tables
UPDATE licensed_client_registry
SET company_name = 'JASVI INDUSTRIES'
WHERE UPPER(TRIM(company_name)) IN ('JASVI INDUTRIES', 'JASVI INDUSTRY');

UPDATE app_license
SET company_name = 'JASVI INDUSTRIES'
WHERE UPPER(TRIM(company_name)) IN ('JASVI INDUTRIES', 'JASVI INDUSTRY');

UPDATE client_profiles
SET client_name = 'JASVI INDUSTRIES', trade_name = 'JASVI INDUSTRIES'
WHERE UPPER(TRIM(client_name)) IN ('JASVI INDUTRIES', 'JASVI INDUSTRY');

-- 2. Clean up legacy hardcoded host VM hardware hashes to allow seamless workstation binding
UPDATE licensed_client_registry
SET machine_id = 'AUTO_BIND_HOST',
    notes = 'Dynamic workstation auto-binding authorized'
WHERE machine_id LIKE 'F12DC2C0%'
   OR machine_id LIKE 'F12D-C2C0%';

UPDATE app_license
SET machine_hash = 'AUTO_BIND_HOST',
    status = 'ACTIVE',
    last_verified_at = CURRENT_TIMESTAMP
WHERE machine_hash LIKE 'F12DC2C0%'
   OR machine_hash LIKE 'F12D-C2C0%';

-- 3. Add deployment_mode and company_name columns to telemetry and session logs
ALTER TABLE client_user_telemetry
    ADD COLUMN IF NOT EXISTS deployment_mode VARCHAR(32) DEFAULT 'LOCAL';

ALTER TABLE client_user_session_log
    ADD COLUMN IF NOT EXISTS deployment_mode VARCHAR(32) DEFAULT 'LOCAL',
    ADD COLUMN IF NOT EXISTS company_name VARCHAR(255);

-- 4. Ensure composite lookup index on client_user_telemetry for company-level aggregation
CREATE INDEX IF NOT EXISTS idx_client_user_telemetry_company_date
    ON client_user_telemetry(company_name, work_date);
