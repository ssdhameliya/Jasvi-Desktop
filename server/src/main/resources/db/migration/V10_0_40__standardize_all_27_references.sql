-- DSE ERP 10.0.40: Reference Scheme Master Authority & Universal 27 Reference Standardization
-- 1. Create REFERENCE_SCHEME category and register the 12 canonical user reference patterns + NONE
-- 2. Standardize all 27+ reference formats in lookup_master to follow the unified standard

INSERT INTO master_category(category_code, category_name, description, display_order, is_active)
VALUES('REFERENCE_SCHEME', 'REFERENCE SCHEME', 'Canonical date and year numbering schemes supported across all ERP reference formats', 210, 1)
ON CONFLICT (category_code) DO UPDATE
SET category_name = EXCLUDED.category_name,
    description = EXCLUDED.description,
    display_order = EXCLUDED.display_order;

INSERT INTO lookup_master(lookup_type, lookup_code, lookup_value, description, display_order, is_active)
SELECT cat.category_name, v.lookup_code, v.lookup_value, v.description, v.display_order, 1
FROM master_category cat
CROSS JOIN (VALUES
    ('SCH001', 'DD-MM-YYYY', 'Day-Month-Year Calendar Date (e.g. 01-11-2026)', 10),
    ('SCH002', 'DD-YYYY-MM', 'Day-Year-Month Calendar Date (e.g. 01-2026-11)', 20),
    ('SCH003', 'MM-DD-YYYY', 'Month-Day-Year US Calendar Date (e.g. 11-01-2026)', 30),
    ('SCH004', 'MM-YYYY-DD', 'Month-Year-Day Calendar Date (e.g. 11-2026-01)', 40),
    ('SCH005', 'YYYY-DD-MM', 'Year-Day-Month Calendar Date (e.g. 2026-01-11)', 50),
    ('SCH006', 'YYYY-MM-DD', 'Year-Month-Day ISO Calendar Date (e.g. 2026-11-01)', 60),
    ('SCH007', 'YY-YYYY', '2-Digit to 4-Digit Financial Year (e.g. 26-2027)', 70),
    ('SCH008', 'YYYY-YY', '4-Digit to 2-Digit Financial Year (e.g. 2026-27)', 80),
    ('SCH009', 'DD-YYYY', 'Day and 4-Digit Year (e.g. 01-2026)', 90),
    ('SCH010', 'YYYY-DD', '4-Digit Year and Day (e.g. 2026-01)', 100),
    ('SCH011', 'MM-YYYY', 'Month and 4-Digit Year (e.g. 11-2026)', 110),
    ('SCH012', 'YYYY-MM', '4-Digit Year and Month (e.g. 2026-11)', 120),
    ('SCH013', 'NONE', 'Pure Sequence Numbering (No Date/Year)', 130)
) AS v(lookup_code, lookup_value, description, display_order)
WHERE cat.category_code = 'REFERENCE_SCHEME'
  AND NOT EXISTS (
      SELECT 1 FROM lookup_master x
      WHERE UPPER(TRIM(x.lookup_type)) = UPPER(TRIM(cat.category_name))
        AND UPPER(TRIM(x.lookup_code)) = UPPER(TRIM(v.lookup_code))
  )
ON CONFLICT DO NOTHING;

-- Standardize all Master Data reference formats in lookup_master under REFERENCE_FORMAT category
UPDATE lookup_master SET lookup_value = 'IN/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_SALES';
UPDATE lookup_master SET lookup_value = 'PUR/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_PURCHASE';
UPDATE lookup_master SET lookup_value = 'QT/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_QUOTATION';
UPDATE lookup_master SET lookup_value = 'SRT/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_SALES_RETURN';
UPDATE lookup_master SET lookup_value = 'PRT/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_PURCHASE_RETURN';
UPDATE lookup_master SET lookup_value = 'VCH/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_FINANCE_VOUCHER';
UPDATE lookup_master SET lookup_value = 'BE/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_BANK_ENTRY';
UPDATE lookup_master SET lookup_value = 'EXP/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_BANK_EXPENSE';
UPDATE lookup_master SET lookup_value = 'BST/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_BANK_STATEMENT';
UPDATE lookup_master SET lookup_value = 'JV/DD-MM-YYYY/XXXX' WHERE lookup_code IN ('REF_JOURNAL', 'JOURNAL_VOUCHER', 'JOURNAL');
UPDATE lookup_master SET lookup_value = 'RSP/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_RECON_SUPPLIER';
UPDATE lookup_master SET lookup_value = 'PRC/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_PURCHASE_RECON';

UPDATE lookup_master SET lookup_value = 'CUS/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_CUSTOMER';
UPDATE lookup_master SET lookup_value = 'SUP/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_SUPPLIER';
UPDATE lookup_master SET lookup_value = 'ITM/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_ITEM';

UPDATE lookup_master SET lookup_value = 'CAT/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_LOOKUP_CATEGORY';
UPDATE lookup_master SET lookup_value = 'UNT/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_LOOKUP_UNIT';
UPDATE lookup_master SET lookup_value = 'MAT/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_LOOKUP_MATERIAL';
UPDATE lookup_master SET lookup_value = 'BRD/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_LOOKUP_BRAND';
UPDATE lookup_master SET lookup_value = 'GST/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_LOOKUP_GST';
UPDATE lookup_master SET lookup_value = 'ROL/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_LOOKUP_ROLE';
UPDATE lookup_master SET lookup_value = 'DSC/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_LOOKUP_DISCOUNT';
UPDATE lookup_master SET lookup_value = 'GTP/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_LOOKUP_GST_TYPE';
UPDATE lookup_master SET lookup_value = 'TRN/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_LOOKUP_TRANSPORTER';
UPDATE lookup_master SET lookup_value = 'PTM/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_LOOKUP_PAYMENT_TERMS';
UPDATE lookup_master SET lookup_value = 'CHG/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_LOOKUP_CHARGES';
UPDATE lookup_master SET lookup_value = 'PMD/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_LOOKUP_PAYMENT_MODE';
UPDATE lookup_master SET lookup_value = 'EXP/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_LOOKUP_EXPENSE_CATEGORY';
UPDATE lookup_master SET lookup_value = 'BNK/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_LOOKUP_BANK_ACCOUNT';
UPDATE lookup_master SET lookup_value = 'QTS/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_LOOKUP_QUOTATION_SOURCE';
UPDATE lookup_master SET lookup_value = 'RFM/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_LOOKUP_REFERENCE_FORMAT';
UPDATE lookup_master SET lookup_value = 'ACT/DD-MM-YYYY/XXXX' WHERE lookup_code = 'REF_LOOKUP_ACCOUNT_TYPE';
