-- V11_0_77 Universal Complete Screen, Tab, and Action Level Permissions
-- Seeds fine-grained permissions for Data Import, Master Data, Audit Trail, Safe Rollback,
-- Backup & Restore, System Diagnostics, Barcode Studio, Document Studio, and Reports.

INSERT INTO permissions(permission_key, module_name, action_name, description, active) VALUES
-- Universal Data Importer capabilities
('IMPORT.EXECUTE', 'IMPORT', 'EXECUTE', 'Execute and apply data import batch to database', 1),
('IMPORT.ITEMS', 'IMPORT', 'ITEMS', 'Import item master and inventory products', 1),
('IMPORT.CUSTOMERS', 'IMPORT', 'CUSTOMERS', 'Import customer directory and accounts', 1),
('IMPORT.SUPPLIERS', 'IMPORT', 'SUPPLIERS', 'Import supplier directory and accounts', 1),
('IMPORT.SALES', 'IMPORT', 'SALES', 'Import historical sales invoices', 1),
('IMPORT.PURCHASES', 'IMPORT', 'PURCHASES', 'Import historical purchase bills', 1),
('IMPORT.BANK', 'IMPORT', 'BANK', 'Import bank statement transactions', 1),
('IMPORT.MASTERS', 'IMPORT', 'MASTERS', 'Import master categories and lookup values', 1),

-- Master Data Category and Lookup mutations
('MASTERS.CREATE_CATEGORY', 'MASTERS', 'CREATE_CATEGORY', 'Add new lookup category', 1),
('MASTERS.EDIT_CATEGORY', 'MASTERS', 'EDIT_CATEGORY', 'Rename existing lookup category', 1),
('MASTERS.DELETE_CATEGORY', 'MASTERS', 'DELETE_CATEGORY', 'Delete lookup category', 1),
('MASTERS.CREATE_LOOKUP', 'MASTERS', 'CREATE_LOOKUP', 'Add new lookup value entry', 1),
('MASTERS.EDIT_LOOKUP', 'MASTERS', 'EDIT_LOOKUP', 'Edit lookup value entry', 1),
('MASTERS.DELETE_LOOKUP', 'MASTERS', 'DELETE_LOOKUP', 'Delete or deactivate lookup value entry', 1),
('MASTERS.EXPORT', 'MASTERS', 'EXPORT', 'Export master lookup values to file', 1),

-- Global Audit Trail capabilities
('AUDIT.EXPORT', 'AUDIT', 'EXPORT', 'Export audit logs to Excel or CSV', 1),
('AUDIT.SECURITY', 'AUDIT', 'SECURITY', 'View authentication, password, and security audit logs', 1),

-- Safe Rollback capabilities
('SAFE_ROLLBACK.EXECUTE', 'SAFE_ROLLBACK', 'EXECUTE', 'Execute live application and database rollback', 1),

-- Backup and Restore capabilities
('BACKUP.CREATE', 'BACKUP', 'CREATE', 'Create on-demand database backup snapshot', 1),
('BACKUP.RESTORE', 'BACKUP', 'RESTORE', 'Restore database from backup snapshot', 1),
('BACKUP.EXPORT', 'BACKUP', 'EXPORT', 'Export recovery archive to external disk', 1),

-- System Diagnostics capabilities
('SYSTEM.VIEW', 'SYSTEM', 'VIEW', 'View system specifications and JVM metrics', 1),
('SYSTEM.LOGS', 'SYSTEM', 'LOGS', 'View live server logs and error traces', 1),
('SYSTEM.CACHE_CLEAR', 'SYSTEM', 'CACHE_CLEAR', 'Evict and reload server and desktop cache', 1),

-- Barcode Studio capabilities
('BARCODE.PRINT', 'BARCODE', 'PRINT', 'Print barcode and QR labels', 1),
('BARCODE.DESIGN', 'BARCODE', 'DESIGN', 'Customize label dimensions and template design', 1),

-- Document Studio capabilities
('DOCUMENT_STUDIO.EDIT', 'DOCUMENT_STUDIO', 'EDIT', 'Edit invoice and document layout templates', 1),
('DOCUMENT_STUDIO.RESET', 'DOCUMENT_STUDIO', 'RESET', 'Restore default factory document templates', 1),

-- Reports capabilities
('REPORTS.EXPORT', 'REPORTS', 'EXPORT', 'Export reports to Excel or PDF', 1)

ON CONFLICT (permission_key) DO UPDATE SET description = EXCLUDED.description, active = 1;

-- Grant default access to ADMIN and SUPER_ADMIN roles
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT 'ADMIN', p.id, 1
FROM permissions p
WHERE p.active = 1
  AND p.permission_key IN (
    'IMPORT.EXECUTE', 'IMPORT.ITEMS', 'IMPORT.CUSTOMERS', 'IMPORT.SUPPLIERS',
    'IMPORT.SALES', 'IMPORT.PURCHASES', 'IMPORT.BANK', 'IMPORT.MASTERS',
    'MASTERS.CREATE_CATEGORY', 'MASTERS.EDIT_CATEGORY', 'MASTERS.DELETE_CATEGORY',
    'MASTERS.CREATE_LOOKUP', 'MASTERS.EDIT_LOOKUP', 'MASTERS.DELETE_LOOKUP', 'MASTERS.EXPORT',
    'AUDIT.EXPORT', 'AUDIT.SECURITY',
    'SAFE_ROLLBACK.EXECUTE',
    'BACKUP.CREATE', 'BACKUP.RESTORE', 'BACKUP.EXPORT',
    'SYSTEM.VIEW', 'SYSTEM.LOGS', 'SYSTEM.CACHE_CLEAR',
    'BARCODE.PRINT', 'BARCODE.DESIGN',
    'DOCUMENT_STUDIO.EDIT', 'DOCUMENT_STUDIO.RESET',
    'REPORTS.EXPORT'
  )
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO UPDATE SET allowed = 1;

INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT 'SUPER_ADMIN', p.id, 1
FROM permissions p
WHERE p.active = 1
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO UPDATE SET allowed = 1;

-- Backward compatibility: propagate existing parent permissions to child actions
-- 1. Roles with IMPORT.VIEW inherit granular import capabilities
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT rp.role_code, target.id, 1
FROM role_permission rp
JOIN permissions source ON source.id = rp.permission_id AND source.permission_key = 'IMPORT.VIEW'
CROSS JOIN permissions target
WHERE rp.allowed = 1
  AND target.permission_key IN ('IMPORT.EXECUTE', 'IMPORT.ITEMS', 'IMPORT.CUSTOMERS', 'IMPORT.SUPPLIERS', 'IMPORT.SALES', 'IMPORT.PURCHASES', 'IMPORT.BANK', 'IMPORT.MASTERS')
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO NOTHING;

-- 2. Roles with MASTERS.VIEW inherit master lookup and category actions
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT rp.role_code, target.id, 1
FROM role_permission rp
JOIN permissions source ON source.id = rp.permission_id AND source.permission_key = 'MASTERS.VIEW'
CROSS JOIN permissions target
WHERE rp.allowed = 1
  AND target.permission_key IN ('MASTERS.CREATE_CATEGORY', 'MASTERS.EDIT_CATEGORY', 'MASTERS.DELETE_CATEGORY', 'MASTERS.CREATE_LOOKUP', 'MASTERS.EDIT_LOOKUP', 'MASTERS.DELETE_LOOKUP', 'MASTERS.EXPORT')
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO NOTHING;

-- 3. Roles with AUDIT.VIEW inherit audit export
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT rp.role_code, target.id, 1
FROM role_permission rp
JOIN permissions source ON source.id = rp.permission_id AND source.permission_key = 'AUDIT.VIEW'
CROSS JOIN permissions target
WHERE rp.allowed = 1
  AND target.permission_key IN ('AUDIT.EXPORT')
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO NOTHING;

-- 4. Roles with BACKUP.VIEW inherit backup create
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT rp.role_code, target.id, 1
FROM role_permission rp
JOIN permissions source ON source.id = rp.permission_id AND source.permission_key = 'BACKUP.VIEW'
CROSS JOIN permissions target
WHERE rp.allowed = 1
  AND target.permission_key IN ('BACKUP.CREATE')
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO NOTHING;

-- 5. Roles with BARCODE.VIEW inherit print
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT rp.role_code, target.id, 1
FROM role_permission rp
JOIN permissions source ON source.id = rp.permission_id AND source.permission_key = 'BARCODE.VIEW'
CROSS JOIN permissions target
WHERE rp.allowed = 1
  AND target.permission_key IN ('BARCODE.PRINT')
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO NOTHING;

-- 6. Roles with REPORTS.VIEW inherit export
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT rp.role_code, target.id, 1
FROM role_permission rp
JOIN permissions source ON source.id = rp.permission_id AND source.permission_key = 'REPORTS.VIEW'
CROSS JOIN permissions target
WHERE rp.allowed = 1
  AND target.permission_key IN ('REPORTS.EXPORT')
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO NOTHING;
