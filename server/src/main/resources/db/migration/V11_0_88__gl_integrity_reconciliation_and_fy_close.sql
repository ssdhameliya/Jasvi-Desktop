-- DSE ERP 11.0.60: General Ledger Integrity, Cross-Module Linking, COA Canonicalization & Year-End Close

-- 1. Link operational sub-ledgers cleanly to their authoritative GL Journal Entry
ALTER TABLE finance_register ADD COLUMN IF NOT EXISTS gl_entry_id BIGINT;
ALTER TABLE payment_record ADD COLUMN IF NOT EXISTS gl_entry_id BIGINT;
ALTER TABLE return_refund ADD COLUMN IF NOT EXISTS gl_entry_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_finance_gl_entry ON finance_register(gl_entry_id);
CREATE INDEX IF NOT EXISTS idx_payment_gl_entry ON payment_record(gl_entry_id);
CREATE INDEX IF NOT EXISTS idx_refund_gl_entry ON return_refund(gl_entry_id);

-- 2. Year-End Fiscal Closing Audit & GST Tax Offset Tracking
CREATE TABLE IF NOT EXISTS fiscal_year_close_log (
    id BIGSERIAL PRIMARY KEY,
    fy_label VARCHAR(40) NOT NULL,
    from_date DATE NOT NULL,
    to_date DATE NOT NULL,
    net_profit NUMERIC(15,2) NOT NULL DEFAULT 0.00,
    retained_earnings_entry_id BIGINT,
    closed_by VARCHAR(160),
    closed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS gst_gl_settlement_log (
    id BIGSERIAL PRIMARY KEY,
    gst_period VARCHAR(20) NOT NULL UNIQUE,
    journal_entry_id BIGINT,
    output_tax NUMERIC(15,2) NOT NULL DEFAULT 0.00,
    input_credit NUMERIC(15,2) NOT NULL DEFAULT 0.00,
    net_cash_payable NUMERIC(15,2) NOT NULL DEFAULT 0.00,
    posted_by VARCHAR(160),
    posted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 3. Canonical Chart of Accounts Additions (zero balances; real postings only)
INSERT INTO chart_of_accounts(account_code, account_name, account_type, account_subtype, is_active, is_system)
VALUES
    ('1510', 'Plant, Equipment & Fixed Assets', 'ASSET', 'FIXED_ASSET', TRUE, TRUE),
    ('2510', 'Long-Term Borrowings & Loans', 'LIABILITY', 'NON_CURRENT_LIABILITY', TRUE, TRUE),
    ('4100', 'Sales Returns & Allowances', 'REVENUE', 'SALES_RETURN', TRUE, TRUE),
    ('5100', 'Purchase Returns & Outward', 'EXPENSE', 'DIRECT_EXPENSE', TRUE, TRUE)
ON CONFLICT (account_code) DO NOTHING;

-- Normalize return ledger types/subtypes if pre-created by earlier adjustment code
UPDATE chart_of_accounts SET account_subtype = 'SALES_RETURN' WHERE account_code = '4100';
UPDATE chart_of_accounts SET account_subtype = 'DIRECT_EXPENSE' WHERE account_code = '5100';

-- 4. Consolidate any legacy split-code journal lines ('1100','2100','2200','1200','8100') onto canonical COA codes
UPDATE journal_line jl
SET account_id = target.id
FROM chart_of_accounts legacy, chart_of_accounts target
WHERE jl.account_id = legacy.id
  AND legacy.account_code = '1100'
  AND target.account_code = '1040'
  AND legacy.id <> target.id;

UPDATE journal_line jl
SET account_id = target.id
FROM chart_of_accounts legacy, chart_of_accounts target
WHERE jl.account_id = legacy.id
  AND legacy.account_code = '2100'
  AND target.account_code = '2010'
  AND legacy.id <> target.id;

UPDATE journal_line jl
SET account_id = target.id
FROM chart_of_accounts legacy, chart_of_accounts target
WHERE jl.account_id = legacy.id
  AND legacy.account_code = '2200'
  AND target.account_code = '2083'
  AND legacy.id <> target.id;

UPDATE journal_line jl
SET account_id = target.id
FROM chart_of_accounts legacy, chart_of_accounts target
WHERE jl.account_id = legacy.id
  AND legacy.account_code = '1200'
  AND target.account_code = '1083'
  AND legacy.id <> target.id;

UPDATE journal_line jl
SET account_id = target.id
FROM chart_of_accounts legacy, chart_of_accounts target
WHERE jl.account_id = legacy.id
  AND legacy.account_code = '8100'
  AND target.account_code = '5050'
  AND legacy.id <> target.id;

-- Deactivate empty duplicate legacy accounts if they have zero opening and zero journal lines
UPDATE chart_of_accounts c
SET is_active = FALSE, is_system = FALSE
WHERE c.account_code IN ('1100', '2100', '2200', '1200', '8100')
  AND COALESCE(c.opening_balance, 0) = 0
  AND NOT EXISTS (SELECT 1 FROM journal_line jl WHERE jl.account_id = c.id);

-- 5. Recompute authoritative current_balance across all COA rows from opening_balance + POSTED/REVERSED lines
UPDATE chart_of_accounts c
SET current_balance = COALESCE(c.opening_balance, 0) + COALESCE((
    SELECT SUM(
        CASE
            WHEN UPPER(c.account_type) IN ('ASSET', 'EXPENSE') THEN COALESCE(jl.debit_amount, 0) - COALESCE(jl.credit_amount, 0)
            ELSE COALESCE(jl.credit_amount, 0) - COALESCE(jl.debit_amount, 0)
        END
    )
    FROM journal_line jl
    JOIN journal_entry je ON je.id = jl.journal_entry_id
    WHERE jl.account_id = c.id
      AND UPPER(COALESCE(je.status, '')) IN ('POSTED', 'REVERSED')
), 0);

-- 6. Granular Permissions Alignment for Year-End Close & Monthly GST Offset Posting
INSERT INTO permissions (permission_key, module_name, action_name, description, active)
VALUES
    ('GENERAL_LEDGER.CLOSE_YEAR', 'GENERAL_LEDGER', 'CLOSE_YEAR', 'Run Year-End Fiscal Close & Retained Earnings Carry-Forward', 1),
    ('GST_COMPLIANCE.POST_GL', 'GST_COMPLIANCE', 'POST_GL', 'Post Monthly GST ITC Utilization Offset Entry to General Ledger', 1),
    ('FINANCIAL_STATEMENTS.VIEW', 'FINANCIAL_STATEMENTS', 'VIEW', 'View Profit & Loss and Balance Sheet', 1),
    ('FINANCIAL_STATEMENTS.EXPORT', 'FINANCIAL_STATEMENTS', 'EXPORT', 'Export Financial Statements', 1),
    ('AGING_ANALYSIS.VIEW', 'AGING_ANALYSIS', 'VIEW', 'View Accounts Receivable / Payable Aging', 1),
    ('AGING_ANALYSIS.REMIND', 'AGING_ANALYSIS', 'REMIND', 'Send Customer Overdue Reminders', 1),
    ('AGING_ANALYSIS.EXPORT', 'AGING_ANALYSIS', 'EXPORT', 'Export Aging Reports', 1)
ON CONFLICT (permission_key) DO UPDATE SET description = EXCLUDED.description, active = 1;

INSERT INTO role_permission (role_code, permission_id, allowed)
SELECT rc.role_code, p.id, 1
FROM (VALUES ('ADMIN'), ('SUPER_ADMIN')) AS rc(role_code)
CROSS JOIN permissions p
WHERE p.permission_key IN (
    'GENERAL_LEDGER.CLOSE_YEAR', 'GST_COMPLIANCE.POST_GL',
    'FINANCIAL_STATEMENTS.VIEW', 'FINANCIAL_STATEMENTS.EXPORT',
    'AGING_ANALYSIS.VIEW', 'AGING_ANALYSIS.REMIND', 'AGING_ANALYSIS.EXPORT'
)
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO UPDATE SET allowed = 1;

INSERT INTO role_permission (role_code, permission_id, allowed)
SELECT 'MANAGER', p.id, 1
FROM permissions p
WHERE p.permission_key IN ('GST_COMPLIANCE.POST_GL', 'GENERAL_LEDGER.CREATE_JOURNAL', 'GENERAL_LEDGER.POST_JOURNAL')
ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
DO NOTHING;
