-- DSE ERP 10.0.39: Bank Statement Unique Reference Authority
-- Adds statement_reference column to bank_statement_import and backfills existing rows.

ALTER TABLE bank_statement_import ADD COLUMN IF NOT EXISTS statement_reference TEXT;

-- Backfill existing statement import rows with unique deterministic reference
UPDATE bank_statement_import
SET statement_reference = 'BST/' || TO_CHAR(COALESCE(imported_at::TIMESTAMP, NOW()), 'YYYY') || '/' || LPAD(id::TEXT, 5, '0')
WHERE statement_reference IS NULL OR TRIM(statement_reference) = '';

-- Guarantee uniqueness
CREATE UNIQUE INDEX IF NOT EXISTS uq_bank_statement_import_ref ON bank_statement_import(statement_reference);
