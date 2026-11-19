-- V11_0_78 Universal Remaining Module Actions and Granular Controls
-- Seeds fine-grained permissions for General Ledger, Financial Statements, GST Compliance,
-- Bill to Tally, Automation Center, Reminder Center, Communication Center, Purchase Recon,
-- and User & Role Administration.

INSERT INTO permissions(permission_key, module_name, action_name, description, active) VALUES
-- General Ledger capabilities
('GENERAL_LEDGER.LOCK_PERIOD', 'GENERAL_LEDGER', 'LOCK_PERIOD', 'Lock or unlock fiscal accounting periods', 1),
('GENERAL_LEDGER.CREATE_JOURNAL', 'GENERAL_LEDGER', 'CREATE_JOURNAL', 'Create manual journal vouchers', 1),
('GENERAL_LEDGER.POST_JOURNAL', 'GENERAL_LEDGER', 'POST_JOURNAL', 'Post draft journal entries to live ledger', 1),
('GENERAL_LEDGER.REVERSE_JOURNAL', 'GENERAL_LEDGER', 'REVERSE_JOURNAL', 'Create accounting reversal vouchers', 1),
('GENERAL_LEDGER.CREATE_ACCOUNT', 'GENERAL_LEDGER', 'CREATE_ACCOUNT', 'Create new Chart of Accounts ledger', 1),
('GENERAL_LEDGER.EDIT_ACCOUNT', 'GENERAL_LEDGER', 'EDIT_ACCOUNT', 'Edit Chart of Accounts ledger properties', 1),
('GENERAL_LEDGER.EXPORT', 'GENERAL_LEDGER', 'EXPORT', 'Export General Ledger and Trial Balance', 1),

-- Financial Statements capabilities
('FINANCIAL_STATEMENTS.EXPORT', 'FINANCIAL_STATEMENTS', 'EXPORT', 'Export P&L and Balance Sheet reports', 1),

-- GST Compliance capabilities
('GST_COMPLIANCE.IMPORT', 'GST_COMPLIANCE', 'IMPORT', 'Import GSTR-2B JSON reconciliation statements', 1),
('GST_COMPLIANCE.EXPORT', 'GST_COMPLIANCE', 'EXPORT', 'Export GSTR-1 returns to JSON or Excel', 1),
('GST_COMPLIANCE.NOTIFY', 'GST_COMPLIANCE', 'NOTIFY', 'Send ITC discrepancy notices to suppliers', 1),

-- Bill to Tally capabilities
('TALLY.UPLOAD', 'TALLY', 'UPLOAD', 'Upload invoices for OCR Bill to Tally conversion', 1),
('TALLY.DELETE', 'TALLY', 'DELETE', 'Delete uploaded Bill to Tally records', 1),
('TALLY.PUSH', 'TALLY', 'PUSH', 'Push transactions directly to Tally XML endpoint', 1),
('TALLY.EXPORT', 'TALLY', 'EXPORT', 'Export Tally XML, JSON, or Excel sheets', 1),

-- Automation Center capabilities
('AUTOMATION.SCAN', 'AUTOMATION', 'SCAN', 'Trigger 3-way matching automated scans', 1),
('AUTOMATION.REORDER_SCAN', 'AUTOMATION', 'REORDER_SCAN', 'Trigger replenishment reorder scans', 1),
('AUTOMATION.CREATE_PO', 'AUTOMATION', 'CREATE_PO', 'Auto-generate Purchase Order from replenishment suggestion', 1),

-- Reminder Center capabilities
('REMINDERS.CREATE', 'REMINDERS', 'CREATE', 'Create manual business follow-up reminders', 1),
('REMINDERS.EDIT', 'REMINDERS', 'EDIT', 'Edit, complete, or snooze reminders', 1),
('REMINDERS.DELETE', 'REMINDERS', 'DELETE', 'Delete business reminders', 1),

-- Communication Center capabilities
('COMMUNICATION.EXPORT', 'COMMUNICATION', 'EXPORT', 'Export email and WhatsApp communication logs', 1),

-- Purchase Recon capabilities
('PURCHASE_RECON.EXPORT', 'PURCHASE_RECON', 'EXPORT', 'Export purchase reconciliation sheets to Excel', 1),

-- User & Role Administration capabilities
('USERS.CREATE', 'USERS', 'CREATE', 'Create new ERP user accounts', 1),
('USERS.EDIT', 'USERS', 'EDIT', 'Modify user details, department, and branch assignments', 1),
('USERS.RESET_PASSWORD', 'USERS', 'RESET_PASSWORD', 'Reset user passwords', 1),
('USERS.MANAGE_PERMISSIONS', 'USERS', 'MANAGE_PERMISSIONS', 'Configure granular role permission matrices', 1),
('USERS.MANAGE_ROLES', 'USERS', 'MANAGE_ROLES', 'Create, edit, or delete custom ERP roles', 1),
('USERS.APPROVE_REGISTRATION', 'USERS', 'APPROVE_REGISTRATION', 'Approve or reject self-registered user accounts', 1)

ON CONFLICT (permission_key) DO UPDATE SET description = EXCLUDED.description, active = 1;

-- Grant default access to ADMIN and SUPER_ADMIN roles
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT 'ADMIN', p.id, 1
FROM permissions p
WHERE p.active = 1
  AND p.permission_key IN (
    'GENERAL_LEDGER.LOCK_PERIOD', 'GENERAL_LEDGER.CREATE_JOURNAL', 'GENERAL_LEDGER.POST_JOURNAL', 'GENERAL_LEDGER.REVERSE_JOURNAL',
    'GENERAL_LEDGER.CREATE_ACCOUNT', 'GENERAL_LEDGER.EDIT_ACCOUNT', 'GENERAL_LEDGER.EXPORT',
    'FINANCIAL_STATEMENTS.EXPORT',
    'GST_COMPLIANCE.IMPORT', 'GST_COMPLIANCE.EXPORT', 'GST_COMPLIANCE.NOTIFY',
    'TALLY.UPLOAD', 'TALLY.DELETE', 'TALLY.PUSH', 'TALLY.EXPORT',
    'AUTOMATION.SCAN', 'AUTOMATION.REORDER_SCAN', 'AUTOMATION.CREATE_PO',
    'REMINDERS.CREATE', 'REMINDERS.EDIT', 'REMINDERS.DELETE',
    'COMMUNICATION.EXPORT',
    'PURCHASE_RECON.EXPORT',
    'USERS.CREATE', 'USERS.EDIT', 'USERS.RESET_PASSWORD', 'USERS.MANAGE_PERMISSIONS', 'USERS.MANAGE_ROLES', 'USERS.APPROVE_REGISTRATION'
  )
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO UPDATE SET allowed = 1;

INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT 'SUPER_ADMIN', p.id, 1
FROM permissions p
WHERE p.active = 1
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO UPDATE SET allowed = 1;

-- Backward compatibility: inherit from parent view permissions where applicable
-- 1. General Ledger
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT rp.role_code, target.id, 1
FROM role_permission rp
JOIN permissions source ON source.id = rp.permission_id AND source.permission_key = 'GENERAL_LEDGER.VIEW'
CROSS JOIN permissions target
WHERE rp.allowed = 1
  AND target.permission_key IN ('GENERAL_LEDGER.CREATE_JOURNAL', 'GENERAL_LEDGER.POST_JOURNAL', 'GENERAL_LEDGER.EXPORT')
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO NOTHING;

-- 2. Financial Statements
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT rp.role_code, target.id, 1
FROM role_permission rp
JOIN permissions source ON source.id = rp.permission_id AND source.permission_key = 'FINANCIAL_STATEMENTS.VIEW'
CROSS JOIN permissions target
WHERE rp.allowed = 1
  AND target.permission_key IN ('FINANCIAL_STATEMENTS.EXPORT')
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO NOTHING;

-- 3. GST Compliance
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT rp.role_code, target.id, 1
FROM role_permission rp
JOIN permissions source ON source.id = rp.permission_id AND source.permission_key = 'GST_COMPLIANCE.VIEW'
CROSS JOIN permissions target
WHERE rp.allowed = 1
  AND target.permission_key IN ('GST_COMPLIANCE.EXPORT', 'GST_COMPLIANCE.NOTIFY')
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO NOTHING;

-- 4. Bill to Tally
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT rp.role_code, target.id, 1
FROM role_permission rp
JOIN permissions source ON source.id = rp.permission_id AND source.permission_key = 'TALLY.VIEW'
CROSS JOIN permissions target
WHERE rp.allowed = 1
  AND target.permission_key IN ('TALLY.UPLOAD', 'TALLY.EXPORT')
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO NOTHING;

-- 5. Automation Center
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT rp.role_code, target.id, 1
FROM role_permission rp
JOIN permissions source ON source.id = rp.permission_id AND source.permission_key = 'AUTOMATION.VIEW'
CROSS JOIN permissions target
WHERE rp.allowed = 1
  AND target.permission_key IN ('AUTOMATION.SCAN', 'AUTOMATION.REORDER_SCAN')
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO NOTHING;

-- 6. Reminders
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT rp.role_code, target.id, 1
FROM role_permission rp
JOIN permissions source ON source.id = rp.permission_id AND source.permission_key = 'REMINDERS.VIEW'
CROSS JOIN permissions target
WHERE rp.allowed = 1
  AND target.permission_key IN ('REMINDERS.CREATE', 'REMINDERS.EDIT')
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO NOTHING;

-- 7. Communication
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT rp.role_code, target.id, 1
FROM role_permission rp
JOIN permissions source ON source.id = rp.permission_id AND source.permission_key = 'COMMUNICATION.VIEW'
CROSS JOIN permissions target
WHERE rp.allowed = 1
  AND target.permission_key IN ('COMMUNICATION.EXPORT')
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO NOTHING;

-- 8. Purchase Recon
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT rp.role_code, target.id, 1
FROM role_permission rp
JOIN permissions source ON source.id = rp.permission_id AND source.permission_key = 'PURCHASE_RECON.VIEW'
CROSS JOIN permissions target
WHERE rp.allowed = 1
  AND target.permission_key IN ('PURCHASE_RECON.EXPORT')
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO NOTHING;
