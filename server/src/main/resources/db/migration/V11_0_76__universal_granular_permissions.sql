-- V11_0_76 Universal Granular Screen, Tab, and Action Level Permissions
-- Seeds fine-grained permissions for all ERP screens, multi-tab modules, and row action buttons.

INSERT INTO permissions(permission_key, module_name, action_name, description, active) VALUES
-- Screen-level capabilities for Finance & Banking
('BANK_EXPENSE.STATEMENT', 'BANK_EXPENSE', 'STATEMENT', 'View and inspect bank statements', 1),
('BANK_EXPENSE.ENTRY', 'BANK_EXPENSE', 'ENTRY', 'Record bank voucher entries', 1),
('BANK_EXPENSE.EXPENSE', 'BANK_EXPENSE', 'EXPENSE', 'Record and manage expense entries', 1),

-- Action-level capabilities for Sales
('SALES.DUPLICATE', 'SALES', 'DUPLICATE', 'Duplicate existing sales invoice', 1),
('SALES.EMAIL', 'SALES', 'EMAIL', 'Send sales invoice via email', 1),
('SALES.WHATSAPP', 'SALES', 'WHATSAPP', 'Send sales invoice via WhatsApp', 1),
('SALES.RETURN', 'SALES', 'RETURN', 'Create sales return from invoice', 1),
('SALES.CANCEL', 'SALES', 'CANCEL', 'Cancel sales invoice', 1),

-- Action-level capabilities for Purchase
('PURCHASE.DUPLICATE', 'PURCHASE', 'DUPLICATE', 'Duplicate existing purchase bill', 1),
('PURCHASE.RETURN', 'PURCHASE', 'RETURN', 'Create purchase return or debit note', 1),
('PURCHASE.CANCEL', 'PURCHASE', 'CANCEL', 'Cancel purchase bill', 1),

-- Action-level capabilities for Quotations
('QUOTATION.DUPLICATE', 'QUOTATION', 'DUPLICATE', 'Duplicate existing quotation', 1),
('QUOTATION.EMAIL', 'QUOTATION', 'EMAIL', 'Send quotation via email', 1),
('QUOTATION.WHATSAPP', 'QUOTATION', 'WHATSAPP', 'Send quotation via WhatsApp', 1),
('QUOTATION.CONVERT_SALE', 'QUOTATION', 'CONVERT_SALE', 'Convert approved quotation to sales invoice', 1),

-- Action-level capabilities for Customers & Suppliers
('CUSTOMERS.360', 'CUSTOMERS', '360', 'View Customer 360 degree profile insights', 1),
('SUPPLIERS.360', 'SUPPLIERS', '360', 'View Supplier 360 degree profile insights', 1),

-- Action-level capabilities for Credit Notes & Debit Notes
('CREDIT_NOTE.CANCEL', 'CREDIT_NOTE', 'CANCEL', 'Cancel credit note', 1),
('DEBIT_NOTE.CANCEL', 'DEBIT_NOTE', 'CANCEL', 'Cancel debit note', 1),

-- Action-level capabilities for Inventory
('INVENTORY.ADJUST', 'INVENTORY', 'ADJUST', 'Perform stock quantity and cost adjustments', 1),

-- Tab-level capabilities for Settings
('SETTINGS.TAB_COMPANY', 'SETTINGS', 'TAB_COMPANY', 'View and manage Company Profile', 1),
('SETTINGS.TAB_EMAIL', 'SETTINGS', 'TAB_EMAIL', 'View and manage Email & SMTP setup', 1),
('SETTINGS.TAB_WHATSAPP', 'SETTINGS', 'TAB_WHATSAPP', 'View and manage WhatsApp channels', 1),
('SETTINGS.TAB_PAYMENT', 'SETTINGS', 'TAB_PAYMENT', 'View and manage Payment & Bank accounts', 1),
('SETTINGS.TAB_INVOICE', 'SETTINGS', 'TAB_INVOICE', 'View and manage Invoice layouts & numbering', 1),
('SETTINGS.TAB_SECURITY', 'SETTINGS', 'TAB_SECURITY', 'Manage user security, PIN & passwords', 1),
('SETTINGS.TAB_NOTIFICATIONS', 'SETTINGS', 'TAB_NOTIFICATIONS', 'Manage system notification preferences', 1),
('SETTINGS.TAB_WORKSPACE', 'SETTINGS', 'TAB_WORKSPACE', 'Configure workstation theme and UI layout', 1),
('SETTINGS.TAB_UPDATES', 'SETTINGS', 'TAB_UPDATES', 'Check and install application updates', 1),
('SETTINGS.TAB_SHORTCUTS', 'SETTINGS', 'TAB_SHORTCUTS', 'Configure keyboard shortcut hotkeys', 1),
('SETTINGS.TAB_MODULES', 'SETTINGS', 'TAB_MODULES', 'Manage ERP module feature entitlements', 1),

-- Tab-level capabilities for Masters
('MASTERS.TAB_CATEGORIES', 'MASTERS', 'TAB_CATEGORIES', 'Manage Item Categories in Masters', 1),
('MASTERS.TAB_UNITS', 'MASTERS', 'TAB_UNITS', 'Manage Units of Measure in Masters', 1),
('MASTERS.TAB_MATERIALS', 'MASTERS', 'TAB_MATERIALS', 'Manage Materials in Masters', 1),
('MASTERS.TAB_BRANDS', 'MASTERS', 'TAB_BRANDS', 'Manage Brands in Masters', 1),
('MASTERS.TAB_GST', 'MASTERS', 'TAB_GST', 'Manage GST Tax Rates in Masters', 1),
('MASTERS.TAB_DISCOUNT', 'MASTERS', 'TAB_DISCOUNT', 'Manage Discount Policies in Masters', 1),
('MASTERS.TAB_PAYMENT_MODES', 'MASTERS', 'TAB_PAYMENT_MODES', 'Manage Payment Modes in Masters', 1),
('MASTERS.TAB_ROLES', 'MASTERS', 'TAB_ROLES', 'Manage Role Descriptions in Masters', 1)
ON CONFLICT (permission_key) DO UPDATE SET description = EXCLUDED.description, active = 1;

-- Seed default full access for ADMIN and SUPER_ADMIN roles
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT 'ADMIN', p.id, 1
FROM permissions p
WHERE p.active = 1
  AND p.permission_key IN (
    'BANK_EXPENSE.STATEMENT', 'BANK_EXPENSE.ENTRY', 'BANK_EXPENSE.EXPENSE',
    'SALES.DUPLICATE', 'SALES.EMAIL', 'SALES.WHATSAPP', 'SALES.RETURN', 'SALES.CANCEL',
    'PURCHASE.DUPLICATE', 'PURCHASE.RETURN', 'PURCHASE.CANCEL',
    'QUOTATION.DUPLICATE', 'QUOTATION.EMAIL', 'QUOTATION.WHATSAPP', 'QUOTATION.CONVERT_SALE',
    'CUSTOMERS.360', 'SUPPLIERS.360',
    'CREDIT_NOTE.CANCEL', 'DEBIT_NOTE.CANCEL', 'INVENTORY.ADJUST',
    'SETTINGS.TAB_COMPANY', 'SETTINGS.TAB_EMAIL', 'SETTINGS.TAB_WHATSAPP', 'SETTINGS.TAB_PAYMENT',
    'SETTINGS.TAB_INVOICE', 'SETTINGS.TAB_SECURITY', 'SETTINGS.TAB_NOTIFICATIONS', 'SETTINGS.TAB_WORKSPACE',
    'SETTINGS.TAB_UPDATES', 'SETTINGS.TAB_SHORTCUTS', 'SETTINGS.TAB_MODULES',
    'MASTERS.TAB_CATEGORIES', 'MASTERS.TAB_UNITS', 'MASTERS.TAB_MATERIALS', 'MASTERS.TAB_BRANDS',
    'MASTERS.TAB_GST', 'MASTERS.TAB_DISCOUNT', 'MASTERS.TAB_PAYMENT_MODES', 'MASTERS.TAB_ROLES'
  )
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO UPDATE SET allowed = 1;

INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT 'SUPER_ADMIN', p.id, 1
FROM permissions p
WHERE p.active = 1
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO UPDATE SET allowed = 1;

-- Backward compatibility: propagate existing broad permissions to granular sub-permissions for legacy roles
-- 1. Roles with BANK_EXPENSE.VIEW inherit sub-screens
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT rp.role_code, target.id, 1
FROM role_permission rp
JOIN permissions source ON source.id = rp.permission_id AND source.permission_key = 'BANK_EXPENSE.VIEW'
CROSS JOIN permissions target
WHERE rp.allowed = 1
  AND target.permission_key IN ('BANK_EXPENSE.STATEMENT', 'BANK_EXPENSE.ENTRY', 'BANK_EXPENSE.EXPENSE')
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO NOTHING;

-- 2. Roles with SETTINGS.VIEW inherit all settings tabs
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT rp.role_code, target.id, 1
FROM role_permission rp
JOIN permissions source ON source.id = rp.permission_id AND source.permission_key = 'SETTINGS.VIEW'
CROSS JOIN permissions target
WHERE rp.allowed = 1
  AND target.module_name = 'SETTINGS' AND target.action_name LIKE 'TAB_%'
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO NOTHING;

-- 3. Roles with MASTERS.VIEW inherit all masters tabs
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT rp.role_code, target.id, 1
FROM role_permission rp
JOIN permissions source ON source.id = rp.permission_id AND source.permission_key = 'MASTERS.VIEW'
CROSS JOIN permissions target
WHERE rp.allowed = 1
  AND target.module_name = 'MASTERS' AND target.action_name LIKE 'TAB_%'
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO NOTHING;

-- 4. Roles with SALES.CREATE or SALES.EDIT inherit standard sales actions
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT rp.role_code, target.id, 1
FROM role_permission rp
JOIN permissions source ON source.id = rp.permission_id AND source.permission_key IN ('SALES.CREATE', 'SALES.EDIT')
CROSS JOIN permissions target
WHERE rp.allowed = 1
  AND target.permission_key IN ('SALES.DUPLICATE', 'SALES.EMAIL', 'SALES.WHATSAPP', 'SALES.RETURN', 'SALES.CANCEL')
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO NOTHING;

-- 5. Roles with PURCHASE.CREATE or PURCHASE.EDIT inherit standard purchase actions
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT rp.role_code, target.id, 1
FROM role_permission rp
JOIN permissions source ON source.id = rp.permission_id AND source.permission_key IN ('PURCHASE.CREATE', 'PURCHASE.EDIT')
CROSS JOIN permissions target
WHERE rp.allowed = 1
  AND target.permission_key IN ('PURCHASE.DUPLICATE', 'PURCHASE.RETURN', 'PURCHASE.CANCEL')
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO NOTHING;

-- 6. Roles with QUOTATION.CREATE or QUOTATION.EDIT inherit quotation actions
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT rp.role_code, target.id, 1
FROM role_permission rp
JOIN permissions source ON source.id = rp.permission_id AND source.permission_key IN ('QUOTATION.CREATE', 'QUOTATION.EDIT')
CROSS JOIN permissions target
WHERE rp.allowed = 1
  AND target.permission_key IN ('QUOTATION.DUPLICATE', 'QUOTATION.EMAIL', 'QUOTATION.WHATSAPP', 'QUOTATION.CONVERT_SALE')
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO NOTHING;

-- 7. Roles with CUSTOMERS.VIEW inherit Customer 360
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT rp.role_code, target.id, 1
FROM role_permission rp
JOIN permissions source ON source.id = rp.permission_id AND source.permission_key = 'CUSTOMERS.VIEW'
CROSS JOIN permissions target
WHERE rp.allowed = 1
  AND target.permission_key = 'CUSTOMERS.360'
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO NOTHING;

-- 8. Roles with SUPPLIERS.VIEW inherit Supplier 360
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT rp.role_code, target.id, 1
FROM role_permission rp
JOIN permissions source ON source.id = rp.permission_id AND source.permission_key = 'SUPPLIERS.VIEW'
CROSS JOIN permissions target
WHERE rp.allowed = 1
  AND target.permission_key = 'SUPPLIERS.360'
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO NOTHING;
