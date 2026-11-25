-- DSE ERP 11.0.79: Unique Per-Row Bank Statement Transaction Line Reference
-- Adds unique transaction_reference (e.g. BST/00001-001) on bank_statement_transaction
-- so every imported transaction has an unmistakable ERP identity separate from batch reference.

ALTER TABLE bank_statement_transaction ADD COLUMN IF NOT EXISTS transaction_reference TEXT;

WITH numbered AS (
    SELECT t.id,
           COALESCE(NULLIF(BTRIM(b.statement_reference), ''), 'BST/IMPORT-' || t.import_id::TEXT)
               || '-' || LPAD(ROW_NUMBER() OVER (PARTITION BY t.import_id ORDER BY COALESCE(t.source_row_number, 0), t.id)::TEXT, 3, '0') AS computed_ref
    FROM bank_statement_transaction t
    LEFT JOIN bank_statement_import b ON b.id = t.import_id
    WHERE t.transaction_reference IS NULL OR BTRIM(t.transaction_reference) = ''
)
UPDATE bank_statement_transaction t
SET transaction_reference = n.computed_ref
FROM numbered n
WHERE t.id = n.id;

CREATE UNIQUE INDEX IF NOT EXISTS uq_bank_statement_transaction_ref ON bank_statement_transaction(transaction_reference);
