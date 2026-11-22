-- V11_0_80 Universal Submenus, Granular Modules, and Screen Action Permissions
-- Completes coverage for all ERP menus, submenus, and action-level capabilities across Jasvi Desktop.

INSERT INTO permissions(permission_key, module_name, action_name, description, active) VALUES
-- =========================================================================
-- 1. SUBMENUS & GRANULAR MODULES
-- =========================================================================
-- Sales Submenus
('SALES_CREATE.VIEW', 'SALES_CREATE', 'VIEW', 'View and open Create Sale invoice screen', 1),
('SALES_CREATE.CREATE', 'SALES_CREATE', 'CREATE', 'Generate and post new sales invoice', 1),
('SALES_CREATE.EDIT', 'SALES_CREATE', 'EDIT', 'Edit unsaved sales invoice draft or items', 1),
('SALES_CREATE.PRICE_OVERRIDE', 'SALES_CREATE', 'PRICE_OVERRIDE', 'Allow overriding standardized unit prices', 1),
('SALES_CREATE.DISCOUNT_OVERRIDE', 'SALES_CREATE', 'DISCOUNT_OVERRIDE', 'Allow granting extra line-item or invoice discounts', 1),
('SALES_CREATE.SAVE_DRAFT', 'SALES_CREATE', 'SAVE_DRAFT', 'Save sales transaction as draft', 1),

('SALES_RETURNS.VIEW', 'SALES_RETURNS', 'VIEW', 'View sales return and credit note register', 1),
('SALES_RETURNS.CREATE', 'SALES_RETURNS', 'CREATE', 'Create sales return or credit note', 1),
('SALES_RETURNS.EDIT', 'SALES_RETURNS', 'EDIT', 'Edit draft sales return entries', 1),
('SALES_RETURNS.DELETE', 'SALES_RETURNS', 'DELETE', 'Delete draft sales return vouchers', 1),
('SALES_RETURNS.EXPORT', 'SALES_RETURNS', 'EXPORT', 'Export sales return reports to Excel or PDF', 1),
('SALES_RETURNS.PRINT', 'SALES_RETURNS', 'PRINT', 'Print sales return vouchers and credit notes', 1),
('SALES_RETURNS.APPROVE', 'SALES_RETURNS', 'APPROVE', 'Authorize sales return stock receipts', 1),

-- Purchase Submenus
('PURCHASE_CREATE.VIEW', 'PURCHASE_CREATE', 'VIEW', 'View and open Create Purchase bill screen', 1),
('PURCHASE_CREATE.CREATE', 'PURCHASE_CREATE', 'CREATE', 'Generate and post new purchase bill', 1),
('PURCHASE_CREATE.EDIT', 'PURCHASE_CREATE', 'EDIT', 'Edit unsaved purchase bill items', 1),
('PURCHASE_CREATE.COST_OVERRIDE', 'PURCHASE_CREATE', 'COST_OVERRIDE', 'Allow overriding default supplier purchase cost', 1),
('PURCHASE_CREATE.SAVE_DRAFT', 'PURCHASE_CREATE', 'SAVE_DRAFT', 'Save purchase transaction as draft', 1),

('PURCHASE_RETURNS.VIEW', 'PURCHASE_RETURNS', 'VIEW', 'View purchase return and debit note register', 1),
('PURCHASE_RETURNS.CREATE', 'PURCHASE_RETURNS', 'CREATE', 'Create purchase return or debit note', 1),
('PURCHASE_RETURNS.EDIT', 'PURCHASE_RETURNS', 'EDIT', 'Edit draft purchase return entries', 1),
('PURCHASE_RETURNS.DELETE', 'PURCHASE_RETURNS', 'DELETE', 'Delete draft purchase return vouchers', 1),
('PURCHASE_RETURNS.EXPORT', 'PURCHASE_RETURNS', 'EXPORT', 'Export purchase return reports to Excel or PDF', 1),
('PURCHASE_RETURNS.PRINT', 'PURCHASE_RETURNS', 'PRINT', 'Print purchase return vouchers and debit notes', 1),
('PURCHASE_RETURNS.APPROVE', 'PURCHASE_RETURNS', 'APPROVE', 'Authorize purchase return inventory returns', 1),

-- Inventory Submenus
('ITEM_MASTER.VIEW', 'ITEM_MASTER', 'VIEW', 'View item master and product catalog', 1),
('ITEM_MASTER.CREATE', 'ITEM_MASTER', 'CREATE', 'Add new inventory items or services', 1),
('ITEM_MASTER.EDIT', 'ITEM_MASTER', 'EDIT', 'Modify item specifications, HSN, and pricing', 1),
('ITEM_MASTER.DELETE', 'ITEM_MASTER', 'DELETE', 'Deactivate or delete inventory items', 1),
('ITEM_MASTER.EXPORT', 'ITEM_MASTER', 'EXPORT', 'Export product catalog to Excel or CSV', 1),
('ITEM_MASTER.ADJUST', 'ITEM_MASTER', 'ADJUST', 'Perform stock quantity adjustments', 1),
('ITEM_MASTER.PRINT_BARCODE', 'ITEM_MASTER', 'PRINT_BARCODE', 'Print barcode labels for inventory products', 1),
('ITEM_MASTER.BULK_IMPORT', 'ITEM_MASTER', 'BULK_IMPORT', 'Bulk import or update catalog products', 1),
('ITEM_MASTER.COST_VIEW', 'ITEM_MASTER', 'COST_VIEW', 'View wholesale purchase cost and profit margins', 1),

-- Bank & Expense Submenus
('BANK_ENTRY.VIEW', 'BANK_ENTRY', 'VIEW', 'View bank voucher entries', 1),
('BANK_ENTRY.CREATE', 'BANK_ENTRY', 'CREATE', 'Create new bank deposit, withdrawal, or transfer', 1),
('BANK_ENTRY.EDIT', 'BANK_ENTRY', 'EDIT', 'Edit existing bank voucher records', 1),
('BANK_ENTRY.DELETE', 'BANK_ENTRY', 'DELETE', 'Delete bank voucher transactions', 1),
('BANK_ENTRY.EXPORT', 'BANK_ENTRY', 'EXPORT', 'Export bank vouchers to Excel or PDF', 1),
('BANK_ENTRY.PRINT_VOUCHER', 'BANK_ENTRY', 'PRINT_VOUCHER', 'Print official bank payment or receipt voucher', 1),
('BANK_ENTRY.CANCEL_ENTRY', 'BANK_ENTRY', 'CANCEL_ENTRY', 'Cancel cleared bank voucher', 1),

('EXPENSE_ENTRY.VIEW', 'EXPENSE_ENTRY', 'VIEW', 'View expense register and payments', 1),
('EXPENSE_ENTRY.CREATE', 'EXPENSE_ENTRY', 'CREATE', 'Record business expense voucher', 1),
('EXPENSE_ENTRY.EDIT', 'EXPENSE_ENTRY', 'EDIT', 'Edit business expense voucher', 1),
('EXPENSE_ENTRY.DELETE', 'EXPENSE_ENTRY', 'DELETE', 'Delete business expense voucher', 1),
('EXPENSE_ENTRY.EXPORT', 'EXPENSE_ENTRY', 'EXPORT', 'Export expense records to Excel or CSV', 1),
('EXPENSE_ENTRY.PRINT_VOUCHER', 'EXPENSE_ENTRY', 'PRINT_VOUCHER', 'Print official expense voucher slip', 1),
('EXPENSE_ENTRY.ATTACHMENT', 'EXPENSE_ENTRY', 'ATTACHMENT', 'Upload, view, and manage expense receipt attachments', 1),

('BANK_STATEMENT.VIEW', 'BANK_STATEMENT', 'VIEW', 'View and inspect bank statements', 1),
('BANK_STATEMENT.CREATE', 'BANK_STATEMENT', 'CREATE', 'Add manual bank statement lines', 1),
('BANK_STATEMENT.EDIT', 'BANK_STATEMENT', 'EDIT', 'Edit unverified bank statement descriptions', 1),
('BANK_STATEMENT.DELETE', 'BANK_STATEMENT', 'DELETE', 'Delete imported bank statement files or records', 1),
('BANK_STATEMENT.EXPORT', 'BANK_STATEMENT', 'EXPORT', 'Export reconciled statements to Excel', 1),
('BANK_STATEMENT.IMPORT', 'BANK_STATEMENT', 'IMPORT', 'Import bank statement files (PDF, Excel, CSV)', 1),
('BANK_STATEMENT.AUTO_MATCH', 'BANK_STATEMENT', 'AUTO_MATCH', 'Execute auto-reconciliation engine', 1),
('BANK_STATEMENT.MANUAL_MATCH', 'BANK_STATEMENT', 'MANUAL_MATCH', 'Manually match statement line to party or ledger', 1),
('BANK_STATEMENT.BULK_EXPENSE', 'BANK_STATEMENT', 'BULK_EXPENSE', 'Bulk convert debits to expense vouchers', 1),
('BANK_STATEMENT.BULK_BANK_ENTRY', 'BANK_STATEMENT', 'BULK_BANK_ENTRY', 'Bulk convert credits to bank voucher entries', 1),
('BANK_STATEMENT.MANAGE_RULES', 'BANK_STATEMENT', 'MANAGE_RULES', 'Create and tune auto-matching keywords and rules', 1),
('BANK_STATEMENT.UNDO_MATCH', 'BANK_STATEMENT', 'UNDO_MATCH', 'Undo reconciled transaction matches', 1),

-- Bill to Tally Submenus
('CLIENTS_DIRECTORY.VIEW', 'CLIENTS_DIRECTORY', 'VIEW', 'View OCR Clients directory for Tally conversion', 1),
('CLIENTS_DIRECTORY.CREATE', 'CLIENTS_DIRECTORY', 'CREATE', 'Add new Client organization profile', 1),
('CLIENTS_DIRECTORY.EDIT', 'CLIENTS_DIRECTORY', 'EDIT', 'Edit Client profile and ledger configurations', 1),
('CLIENTS_DIRECTORY.DELETE', 'CLIENTS_DIRECTORY', 'DELETE', 'Delete Client organization profile', 1),
('CLIENTS_DIRECTORY.EXPORT', 'CLIENTS_DIRECTORY', 'EXPORT', 'Export client directory to Excel', 1),
('CLIENTS_DIRECTORY.SYNC_TALLY', 'CLIENTS_DIRECTORY', 'SYNC_TALLY', 'Trigger live Tally Prime synchronization', 1),
('CLIENTS_DIRECTORY.IMPORT_TALLY', 'CLIENTS_DIRECTORY', 'IMPORT_TALLY', 'Import ledgers and groups from Tally', 1),

('CLIENT_TEMPLATES.VIEW', 'CLIENT_TEMPLATES', 'VIEW', 'View Bill to Tally OCR extraction templates', 1),
('CLIENT_TEMPLATES.CREATE', 'CLIENT_TEMPLATES', 'CREATE', 'Create custom invoice extraction template', 1),
('CLIENT_TEMPLATES.EDIT', 'CLIENT_TEMPLATES', 'EDIT', 'Edit OCR field mapping rules and regex', 1),
('CLIENT_TEMPLATES.DELETE', 'CLIENT_TEMPLATES', 'DELETE', 'Delete OCR extraction templates', 1),
('CLIENT_TEMPLATES.EXPORT', 'CLIENT_TEMPLATES', 'EXPORT', 'Export extraction templates to JSON', 1),
('CLIENT_TEMPLATES.DESIGN', 'CLIENT_TEMPLATES', 'DESIGN', 'Design interactive visual template coordinates', 1),
('CLIENT_TEMPLATES.CLONE', 'CLIENT_TEMPLATES', 'CLONE', 'Clone existing template configuration', 1),

-- Document Studio Submenus
('PDF_STUDIO.VIEW', 'PDF_STUDIO', 'VIEW', 'View PDF Document Designer and templates', 1),
('PDF_STUDIO.CREATE', 'PDF_STUDIO', 'CREATE', 'Create new custom PDF invoice layout', 1),
('PDF_STUDIO.EDIT', 'PDF_STUDIO', 'EDIT', 'Modify layout blocks, typography, and margins', 1),
('PDF_STUDIO.DELETE', 'PDF_STUDIO', 'DELETE', 'Delete custom PDF layout templates', 1),
('PDF_STUDIO.EXPORT', 'PDF_STUDIO', 'EXPORT', 'Export PDF layout templates or test samples', 1),
('PDF_STUDIO.DESIGN', 'PDF_STUDIO', 'DESIGN', 'Open interactive visual PDF drag-and-drop studio', 1),
('PDF_STUDIO.RESET', 'PDF_STUDIO', 'RESET', 'Restore standard factory PDF templates', 1),

('EXCEL_STUDIO.VIEW', 'EXCEL_STUDIO', 'VIEW', 'View Excel Document Designer and sheets', 1),
('EXCEL_STUDIO.CREATE', 'EXCEL_STUDIO', 'CREATE', 'Create new custom Excel export layout', 1),
('EXCEL_STUDIO.EDIT', 'EXCEL_STUDIO', 'EDIT', 'Modify Excel formula bindings and column mappings', 1),
('EXCEL_STUDIO.DELETE', 'EXCEL_STUDIO', 'DELETE', 'Delete custom Excel export templates', 1),
('EXCEL_STUDIO.EXPORT', 'EXCEL_STUDIO', 'EXPORT', 'Export custom Excel test workbooks', 1),
('EXCEL_STUDIO.DESIGN', 'EXCEL_STUDIO', 'DESIGN', 'Open visual Excel column designer', 1),
('EXCEL_STUDIO.RESET', 'EXCEL_STUDIO', 'RESET', 'Restore standard factory Excel templates', 1),

-- Administration Submenus
('ROLE_MANAGEMENT.VIEW', 'ROLE_MANAGEMENT', 'VIEW', 'View enterprise role directory', 1),
('ROLE_MANAGEMENT.CREATE', 'ROLE_MANAGEMENT', 'CREATE', 'Create new custom user role', 1),
('ROLE_MANAGEMENT.EDIT', 'ROLE_MANAGEMENT', 'EDIT', 'Modify role descriptions and privileges', 1),
('ROLE_MANAGEMENT.DELETE', 'ROLE_MANAGEMENT', 'DELETE', 'Deactivate or delete custom roles', 1),
('ROLE_MANAGEMENT.EXPORT', 'ROLE_MANAGEMENT', 'EXPORT', 'Export role configurations to file', 1),
('ROLE_MANAGEMENT.CLONE_ROLE', 'ROLE_MANAGEMENT', 'CLONE_ROLE', 'Duplicate permissions from an existing role', 1),
('ROLE_MANAGEMENT.ASSIGN_USERS', 'ROLE_MANAGEMENT', 'ASSIGN_USERS', 'Bulk assign or migrate users to role', 1),

('REGISTRATION_APPROVALS.VIEW', 'REGISTRATION_APPROVALS', 'VIEW', 'View pending self-registered user accounts', 1),
('REGISTRATION_APPROVALS.EXPORT', 'REGISTRATION_APPROVALS', 'EXPORT', 'Export user registration request logs', 1),
('REGISTRATION_APPROVALS.APPROVE', 'REGISTRATION_APPROVALS', 'APPROVE', 'Approve pending user registration and assign role', 1),
('REGISTRATION_APPROVALS.REJECT', 'REGISTRATION_APPROVALS', 'REJECT', 'Reject pending user registration requests', 1),

('PERMISSION_MATRIX.VIEW', 'PERMISSION_MATRIX', 'VIEW', 'Inspect role permission and access matrix', 1),
('PERMISSION_MATRIX.EDIT', 'PERMISSION_MATRIX', 'EDIT', 'Modify role capabilities, toggles, and templates', 1),
('PERMISSION_MATRIX.EXPORT', 'PERMISSION_MATRIX', 'EXPORT', 'Export permission matrix audit report', 1),

-- =========================================================================
-- 2. COMPREHENSIVE ACTION-LEVEL COVERAGE FOR EXISTING SCREENS
-- =========================================================================
-- Sales Register actions
('SALES.PRINT', 'SALES', 'PRINT', 'Print official GST tax invoice documents', 1),
('SALES.RECORD_PAYMENT', 'SALES', 'RECORD_PAYMENT', 'Record incoming customer payment against invoice', 1),
('SALES.PRICE_OVERRIDE', 'SALES', 'PRICE_OVERRIDE', 'Allow modifying unit rates on sales invoices', 1),

-- Quotation actions
('QUOTATION.PRINT', 'QUOTATION', 'PRINT', 'Print quotation estimates', 1),
('QUOTATION.FOLLOW_UP', 'QUOTATION', 'FOLLOW_UP', 'Log sales customer follow-ups and notes', 1),

-- Purchase Register actions
('PURCHASE.EMAIL', 'PURCHASE', 'EMAIL', 'Send purchase order to supplier via email', 1),
('PURCHASE.WHATSAPP', 'PURCHASE', 'WHATSAPP', 'Send purchase order to supplier via WhatsApp', 1),
('PURCHASE.PRINT', 'PURCHASE', 'PRINT', 'Print purchase bills and orders', 1),
('PURCHASE.RECORD_PAYMENT', 'PURCHASE', 'RECORD_PAYMENT', 'Record outgoing supplier payment against bill', 1),

-- Customer Master & CRM actions
('CUSTOMERS.MANAGE_CONTACTS', 'CUSTOMERS', 'MANAGE_CONTACTS', 'Manage multiple contact persons and notification preferences', 1),
('CUSTOMERS.SEND_STATEMENT', 'CUSTOMERS', 'SEND_STATEMENT', 'Send account statement to customer', 1),
('CUSTOMERS.SEND_LEDGER', 'CUSTOMERS', 'SEND_LEDGER', 'Send ledger transactions via WhatsApp/Email', 1),

-- Supplier Master & HRM actions
('SUPPLIERS.MANAGE_CONTACTS', 'SUPPLIERS', 'MANAGE_CONTACTS', 'Manage multiple contact persons and notification preferences', 1),
('SUPPLIERS.SEND_STATEMENT', 'SUPPLIERS', 'SEND_STATEMENT', 'Send account statement to supplier', 1),
('SUPPLIERS.SEND_LEDGER', 'SUPPLIERS', 'SEND_LEDGER', 'Send ledger transactions via WhatsApp/Email', 1),

-- Credit Notes actions
('CREDIT_NOTE.REFUND_SETTLE', 'CREDIT_NOTE', 'REFUND_SETTLE', 'Settle credit note or issue cash refund', 1),
('CREDIT_NOTE.PRINT', 'CREDIT_NOTE', 'PRINT', 'Print credit note vouchers', 1),

-- Debit Notes actions
('DEBIT_NOTE.REFUND_SETTLE', 'DEBIT_NOTE', 'REFUND_SETTLE', 'Settle debit note or record supplier refund', 1),
('DEBIT_NOTE.PRINT', 'DEBIT_NOTE', 'PRINT', 'Print debit note vouchers', 1),

-- Inventory actions
('INVENTORY.PRINT_BARCODE', 'INVENTORY', 'PRINT_BARCODE', 'Print barcode labels for inventory products', 1),
('INVENTORY.BULK_IMPORT', 'INVENTORY', 'BULK_IMPORT', 'Quick bulk import or update inventory items', 1),
('INVENTORY.COST_VIEW', 'INVENTORY', 'COST_VIEW', 'View wholesale purchase cost and profit margins', 1),

-- GST Compliance actions
('GST_COMPLIANCE.GENERATE_JSON', 'GST_COMPLIANCE', 'GENERATE_JSON', 'Generate official GSTR-1 and GSTR-3B JSON files', 1),
('GST_COMPLIANCE.RECONCILE_2B', 'GST_COMPLIANCE', 'RECONCILE_2B', 'Execute automated GSTR-2B purchase reconciliation', 1),

-- E-Way Bill actions
('EWAY_BILL.PRINT', 'EWAY_BILL', 'PRINT', 'Print official E-Way Bill and E-Invoice slips', 1),
('EWAY_BILL.CANCEL', 'EWAY_BILL', 'CANCEL', 'Cancel generated E-Way Bill or IRN', 1),
('EWAY_BILL.UPDATE_VEHICLE', 'EWAY_BILL', 'UPDATE_VEHICLE', 'Update Part-B vehicle and transporter details', 1),

-- Safe Rollback actions
('SAFE_ROLLBACK.VERIFY_HASH', 'SAFE_ROLLBACK', 'VERIFY_HASH', 'Verify cryptographic checksums before rollback', 1)

ON CONFLICT (permission_key) DO UPDATE SET description = EXCLUDED.description, active = 1;

-- =========================================================================
-- 3. GRANT FULL ACCESS TO ADMIN AND SUPER_ADMIN
-- =========================================================================
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT 'ADMIN', p.id, 1
FROM permissions p
WHERE p.active = 1
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO UPDATE SET allowed = 1;

INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT 'SUPER_ADMIN', p.id, 1
FROM permissions p
WHERE p.active = 1
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO UPDATE SET allowed = 1;

-- =========================================================================
-- 4. BACKWARD COMPATIBILITY: PROPAGATE PARENT PERMISSIONS TO CHILD SUBMENUS
-- =========================================================================
-- Roles with SALES access inherit Sales Submenus
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT rp.role_code, target.id, 1
FROM role_permission rp
JOIN permissions source ON source.id = rp.permission_id AND source.permission_key = 'SALES.VIEW'
CROSS JOIN permissions target
WHERE rp.allowed = 1
  AND target.permission_key IN ('SALES_CREATE.VIEW', 'SALES_RETURNS.VIEW', 'SALES.PRINT', 'SALES.RECORD_PAYMENT')
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO NOTHING;

-- Roles with PURCHASE access inherit Purchase Submenus
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT rp.role_code, target.id, 1
FROM role_permission rp
JOIN permissions source ON source.id = rp.permission_id AND source.permission_key = 'PURCHASE.VIEW'
CROSS JOIN permissions target
WHERE rp.allowed = 1
  AND target.permission_key IN ('PURCHASE_CREATE.VIEW', 'PURCHASE_RETURNS.VIEW', 'PURCHASE.PRINT', 'PURCHASE.RECORD_PAYMENT')
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO NOTHING;

-- Roles with BANK_EXPENSE access inherit Bank Entry, Expense Entry, Bank Statement
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT rp.role_code, target.id, 1
FROM role_permission rp
JOIN permissions source ON source.id = rp.permission_id AND source.permission_key = 'BANK_EXPENSE.VIEW'
CROSS JOIN permissions target
WHERE rp.allowed = 1
  AND target.permission_key IN ('BANK_ENTRY.VIEW', 'EXPENSE_ENTRY.VIEW', 'BANK_STATEMENT.VIEW')
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO NOTHING;

-- Roles with TALLY access inherit Clients Directory & Client Templates
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT rp.role_code, target.id, 1
FROM role_permission rp
JOIN permissions source ON source.id = rp.permission_id AND source.permission_key = 'TALLY.VIEW'
CROSS JOIN permissions target
WHERE rp.allowed = 1
  AND target.permission_key IN ('CLIENTS_DIRECTORY.VIEW', 'CLIENT_TEMPLATES.VIEW')
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO NOTHING;

-- Roles with DOCUMENT_STUDIO access inherit PDF Studio & Excel Studio
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT rp.role_code, target.id, 1
FROM role_permission rp
JOIN permissions source ON source.id = rp.permission_id AND source.permission_key = 'DOCUMENT_STUDIO.VIEW'
CROSS JOIN permissions target
WHERE rp.allowed = 1
  AND target.permission_key IN ('PDF_STUDIO.VIEW', 'EXCEL_STUDIO.VIEW')
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO NOTHING;

-- Roles with USERS access inherit Role Management, Registration Approvals, Permission Matrix
INSERT INTO role_permission(role_code, permission_id, allowed)
SELECT rp.role_code, target.id, 1
FROM role_permission rp
JOIN permissions source ON source.id = rp.permission_id AND source.permission_key = 'USERS.VIEW'
CROSS JOIN permissions target
WHERE rp.allowed = 1
  AND target.permission_key IN ('ROLE_MANAGEMENT.VIEW', 'REGISTRATION_APPROVALS.VIEW', 'PERMISSION_MATRIX.VIEW')
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO NOTHING;
