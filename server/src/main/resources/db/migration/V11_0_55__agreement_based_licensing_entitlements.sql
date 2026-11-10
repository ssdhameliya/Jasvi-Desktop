-- ============================================================================
-- DSE ERP 11.0.55: Agreement-Based Dynamic Licensing & Custom Entitlements
-- ============================================================================

-- 1. Add custom features column to licensed_client_registry
ALTER TABLE licensed_client_registry ADD COLUMN IF NOT EXISTS features TEXT;

-- 2. Populate default features based on tier for existing client records
UPDATE licensed_client_registry
SET features = CASE
    WHEN UPPER(TRIM(tier)) = 'PLATINUM' THEN 'CORE_FINANCE,PRINT_EXPORT,DATA_IMPORTER,EWAY_BILL,CLOUD_BACKUP,WHATSAPP_ROUTING,BANK_RECON,TALLY_SYNC,MULTI_BRANCH,OFFLINE_SYNC'
    WHEN UPPER(TRIM(tier)) = 'SILVER' THEN 'CORE_FINANCE,PRINT_EXPORT,DATA_IMPORTER'
    ELSE 'CORE_FINANCE,PRINT_EXPORT,DATA_IMPORTER,EWAY_BILL,CLOUD_BACKUP,WHATSAPP_ROUTING,BANK_RECON'
END
WHERE features IS NULL OR TRIM(features) = '';

-- 3. Update Anchor Headquarter Master Record with full Platinum Entitlements
UPDATE licensed_client_registry
SET features = 'CORE_FINANCE,PRINT_EXPORT,DATA_IMPORTER,EWAY_BILL,CLOUD_BACKUP,WHATSAPP_ROUTING,BANK_RECON,TALLY_SYNC,MULTI_BRANCH,OFFLINE_SYNC'
WHERE company_name = 'JASVI INDUSTRIES';

-- 4. Create Index on Tier
CREATE INDEX IF NOT EXISTS idx_licensed_client_tier ON licensed_client_registry(tier);

-- 5. Complete Credit Note and Debit Note Standard Matrix Actions
INSERT INTO permissions (permission_key, module_name, action_name, description, active)
VALUES
    ('CREDIT_NOTE.DELETE', 'CREDIT_NOTE', 'DELETE', 'Delete eligible Sales Credit Notes', 1),
    ('CREDIT_NOTE.EXPORT', 'CREDIT_NOTE', 'EXPORT', 'Export Sales Credit Notes register', 1),
    ('DEBIT_NOTE.DELETE', 'DEBIT_NOTE', 'DELETE', 'Delete eligible Purchase Debit Notes', 1),
    ('DEBIT_NOTE.EXPORT', 'DEBIT_NOTE', 'EXPORT', 'Export Purchase Debit Notes register', 1)
ON CONFLICT (permission_key) DO UPDATE SET description = EXCLUDED.description, active = 1;

INSERT INTO role_permission (role_code, permission_id, allowed)
SELECT 'ADMIN', p.id, 1
FROM permissions p
WHERE p.active = 1
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO NOTHING;

