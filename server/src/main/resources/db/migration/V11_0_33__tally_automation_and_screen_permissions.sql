-- V11_0_33__tally_automation_and_screen_permissions.sql
-- Aligns User Access & Permission Matrix for v11.0.33:
-- Bill to Tally Automation module permissions and role grants

INSERT INTO permissions(permission_key, module_name, action_name, description) VALUES
('TALLY.VIEW', 'TALLY', 'VIEW', 'Open Bill to Tally Automation workspace and review batches'),
('TALLY.IMPORT', 'TALLY', 'IMPORT', 'Upload bills (Images/PDFs/Excel) and process OCR batches'),
('TALLY.EDIT', 'TALLY', 'EDIT', 'Edit extracted bill data and Tally ledger/item mappings'),
('TALLY.EXPORT', 'TALLY', 'EXPORT', 'Export Tally JSON and Excel voucher files'),
('TALLY.PUSH', 'TALLY', 'PUSH', 'Direct sync to TallyPrime (Port 9000)')
ON CONFLICT (permission_key) DO UPDATE SET description = EXCLUDED.description, active = 1;

-- Grant all to ADMIN role
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT 'ADMIN', p.id, 1
FROM permissions p
WHERE p.module_name = 'TALLY'
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO UPDATE SET allowed = 1;

-- Grant operational permissions to MANAGER role
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT 'MANAGER', p.id, 1
FROM permissions p
WHERE p.permission_key IN (
    'TALLY.VIEW', 'TALLY.IMPORT', 'TALLY.EDIT', 'TALLY.EXPORT', 'TALLY.PUSH'
)
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO UPDATE SET allowed = 1;
