-- V11_0_86: Multi-Company Document Template Linking & Isolation
-- 1. Create company_template_mapping table
CREATE TABLE IF NOT EXISTS company_template_mapping (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL,
    operation_type VARCHAR(32) NOT NULL, -- 'SALES', 'PURCHASE', 'QUOTATION', 'RETURNS'
    template_code VARCHAR(64) NOT NULL,  -- e.g. 'Jasvi_Sale', 'CompanyA_Sale'
    template_name VARCHAR(120) NOT NULL,
    updated_by VARCHAR(64) NOT NULL DEFAULT 'superadmin',
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_company_template_operation UNIQUE (company_id, operation_type)
);

CREATE INDEX IF NOT EXISTS idx_comp_tpl_map_lookup 
    ON company_template_mapping(company_id, operation_type);

-- 2. Add company_id to core transaction headers if not present
ALTER TABLE sales_header ADD COLUMN IF NOT EXISTS company_id BIGINT DEFAULT 1;
ALTER TABLE purchase_header ADD COLUMN IF NOT EXISTS company_id BIGINT DEFAULT 1;
ALTER TABLE quotation_header ADD COLUMN IF NOT EXISTS company_id BIGINT DEFAULT 1;
ALTER TABLE return_register ADD COLUMN IF NOT EXISTS company_id BIGINT DEFAULT 1;

-- 3. Seed default Master templates for Company ID 1 (JASVI INDUSTRIES)
INSERT INTO company_template_mapping (company_id, operation_type, template_code, template_name, updated_by)
VALUES 
    (1, 'SALES',     'Jasvi_Sale',      'Jasvi Flagship GST Tax Invoice (Core)', 'superadmin'),
    (1, 'PURCHASE',  'Jasvi_Purchase',  'Jasvi Standard Purchase Order (Core)',   'superadmin'),
    (1, 'QUOTATION', 'Jasvi_Quotation', 'Jasvi Machinery Commercial Proposal (Core)', 'superadmin'),
    (1, 'RETURNS',   'Jasvi_Return',    'Jasvi GST Credit & Debit Note (Core)',  'superadmin')
ON CONFLICT (company_id, operation_type) DO UPDATE SET
    template_code = EXCLUDED.template_code,
    template_name = EXCLUDED.template_name,
    updated_at = CURRENT_TIMESTAMP;
