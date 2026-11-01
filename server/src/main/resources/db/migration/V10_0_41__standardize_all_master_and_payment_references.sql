-- DSE ERP 10.0.41: Universal Master & Document Reference Format Standardization + Payment Reference (PRF)
-- 1. Add payment_reference column to payment_record and backfill historical rows
-- 2. Register all missing Master & Document Reference Formats under REFERENCE FORMAT

ALTER TABLE payment_record ADD COLUMN IF NOT EXISTS payment_reference VARCHAR(120);
CREATE INDEX IF NOT EXISTS ix_payment_record_payment_reference ON payment_record(payment_reference);

-- Backfill existing payment_record rows with canonical PRF/DD-MM-YYYY/XXXX where missing
UPDATE payment_record
SET payment_reference = 'PRF/' ||
    CASE
        WHEN COALESCE(payment_date, '') ~ '^\d{4}-\d{2}-\d{2}' THEN
            SUBSTRING(payment_date FROM 9 FOR 2) || '-' || SUBSTRING(payment_date FROM 6 FOR 2) || '-' || SUBSTRING(payment_date FROM 1 FOR 4)
        ELSE TO_CHAR(CURRENT_DATE, 'DD-MM-YYYY')
    END
    || '/' || LPAD(id::text, 4, '0')
WHERE payment_reference IS NULL OR BTRIM(payment_reference) = '';

-- Register all missing Reference Formats under REFERENCE FORMAT
INSERT INTO lookup_master(lookup_type, lookup_code, lookup_value, description, display_order, is_active)
SELECT cat.category_name, v.lookup_code, v.lookup_value, v.description, v.display_order, 1
FROM master_category cat
CROSS JOIN (VALUES
    ('REF_PAYMENT', 'PRF/DD-MM-YYYY/XXXX', 'Payment settlement reference (Sales & Purchase)', 91),
    ('REF_CREDIT_NOTE', 'CN/DD-MM-YYYY/XXXX', 'Sales Credit Note reference', 45),
    ('REF_DEBIT_NOTE', 'DN/DD-MM-YYYY/XXXX', 'Purchase Debit Note reference', 55),
    ('REF_LOOKUP_BUSINESS_TYPE', 'BUS/DD-MM-YYYY/XXXX', 'Business Type Master code reference', 365),
    ('REF_LOOKUP_INDUSTRY', 'IND/DD-MM-YYYY/XXXX', 'Industry Master code reference', 370),
    ('REF_LOOKUP_CURRENCY', 'CCY/DD-MM-YYYY/XXXX', 'Currency Master code reference', 375),
    ('REF_LOOKUP_TIME_ZONE', 'TMZ/DD-MM-YYYY/XXXX', 'Time Zone Master code reference', 380),
    ('REF_LOOKUP_DATE_FORMAT', 'DTF/DD-MM-YYYY/XXXX', 'Date Format Master code reference', 385),
    ('REF_LOOKUP_TIME_FORMAT', 'TMF/DD-MM-YYYY/XXXX', 'Time Format Master code reference', 390),
    ('REF_LOOKUP_REFERENCE_SCHEME', 'SCH/DD-MM-YYYY/XXXX', 'Reference Scheme Master code reference', 395),
    ('REF_LOOKUP_CREDIT_NOTE_REASON', 'CNR/DD-MM-YYYY/XXXX', 'Credit Note Reason Master code reference', 400),
    ('REF_LOOKUP_DEBIT_NOTE_REASON', 'DNR/DD-MM-YYYY/XXXX', 'Debit Note Reason Master code reference', 405)
) AS v(lookup_code, lookup_value, description, display_order)
WHERE cat.category_code = 'REFERENCE_FORMAT'
  AND NOT EXISTS (
      SELECT 1 FROM lookup_master x
      WHERE UPPER(TRIM(x.lookup_type)) = UPPER(TRIM(cat.category_name))
        AND UPPER(TRIM(x.lookup_code)) = UPPER(TRIM(v.lookup_code))
  )
ON CONFLICT DO NOTHING;

UPDATE lookup_master SET lookup_value = 'PRF/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_PAYMENT';
UPDATE lookup_master SET lookup_value = 'CN/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_CREDIT_NOTE';
UPDATE lookup_master SET lookup_value = 'DN/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_DEBIT_NOTE';
UPDATE lookup_master SET lookup_value = 'BUS/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_LOOKUP_BUSINESS_TYPE';
UPDATE lookup_master SET lookup_value = 'IND/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_LOOKUP_INDUSTRY';
UPDATE lookup_master SET lookup_value = 'CCY/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_LOOKUP_CURRENCY';
UPDATE lookup_master SET lookup_value = 'TMZ/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_LOOKUP_TIME_ZONE';
UPDATE lookup_master SET lookup_value = 'DTF/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_LOOKUP_DATE_FORMAT';
UPDATE lookup_master SET lookup_value = 'TMF/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_LOOKUP_TIME_FORMAT';
UPDATE lookup_master SET lookup_value = 'SCH/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_LOOKUP_REFERENCE_SCHEME';
UPDATE lookup_master SET lookup_value = 'CNR/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_LOOKUP_CREDIT_NOTE_REASON';
UPDATE lookup_master SET lookup_value = 'DNR/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_LOOKUP_DEBIT_NOTE_REASON';
