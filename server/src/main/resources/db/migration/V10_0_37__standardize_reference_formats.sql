-- DSE ERP 10.0.37: Standardize all document reference formats to clean unified PREFIX/DD-MM-YYYY/XXXX
-- Upgrades legacy disjoint reference patterns to unified standard while preserving user custom values.

UPDATE lookup_master
SET lookup_value = 'QT/DD-MM-YYYY/XXXX'
WHERE lookup_code = 'REF_QUOTATION'
  AND (lookup_value = 'QT-YYYY-XXXX' OR lookup_value IS NULL);

UPDATE lookup_master
SET lookup_value = 'SRT/DD-MM-YYYY/XXXX'
WHERE lookup_code = 'REF_SALES_RETURN'
  AND (lookup_value = 'SAL-RET-YYYY-XXXX' OR lookup_value IS NULL);

UPDATE lookup_master
SET lookup_value = 'PRT/DD-MM-YYYY/XXXX'
WHERE lookup_code = 'REF_PURCHASE_RETURN'
  AND (lookup_value = 'PUR-RET-YYYY-XXXX' OR lookup_value IS NULL);

UPDATE lookup_master
SET lookup_value = 'VCH/DD-MM-YYYY/XXXX'
WHERE lookup_code = 'REF_FINANCE_VOUCHER'
  AND (lookup_value = 'VCH-YYYY-XXXXX' OR lookup_value IS NULL);

UPDATE lookup_master
SET lookup_value = 'EXP/DD-MM-YYYY/XXXX'
WHERE lookup_code = 'REF_BANK_EXPENSE'
  AND (lookup_value = 'EXP-YYYY-XXXXX' OR lookup_value IS NULL);

UPDATE lookup_master
SET lookup_value = 'BST/DD-MM-YYYY/XXXX'
WHERE lookup_code = 'REF_BANK_STATEMENT'
  AND (lookup_value = 'BST/XXXXX' OR lookup_value IS NULL);

UPDATE lookup_master
SET lookup_value = 'JV/DD-MM-YYYY/XXXX'
WHERE lookup_code IN ('REF_JOURNAL', 'JOURNAL_VOUCHER', 'JOURNAL')
  AND (lookup_value = 'JV-YYYYMMDD-XXXX' OR lookup_value IS NULL);

UPDATE lookup_master
SET lookup_value = 'RSP/DD-MM-YYYY/XXXX'
WHERE lookup_code = 'REF_RECON_SUPPLIER'
  AND (lookup_value = 'RSP-YYYY-XXXXX' OR lookup_value IS NULL);

UPDATE lookup_master
SET lookup_value = 'PRC/DD-MM-YYYY/XXXX'
WHERE lookup_code = 'REF_PURCHASE_RECON'
  AND (lookup_value = 'PRC-YYYY-XXXXX' OR lookup_value IS NULL);
