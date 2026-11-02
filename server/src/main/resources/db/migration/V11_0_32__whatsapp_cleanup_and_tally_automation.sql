-- DSE ERP 11.0.32: WhatsApp Channel Cleanup & Multi-Format Bill-to-Tally Automation Schema
-- 1. WhatsApp Channel Cleanup: Remove dummy placeholder channels with blank phone numbers
DELETE FROM whatsapp_sender_channel
WHERE phone_number IS NULL OR BTRIM(phone_number) = '' OR channel_code = 'PRIMARY_WHATSAPP';

-- 2. Consolidate active lookup sender channels
UPDATE lookup_master
SET lookup_type = 'WHATSAPP_SENDER'
WHERE UPPER(lookup_type) = 'WHATSAPP SENDERS' OR UPPER(lookup_type) = 'WHATSAPP_SENDERS';

-- 3. Multi-Format Bill-to-Tally Automation: Client Profiles Table
CREATE TABLE IF NOT EXISTS client_profiles (
    id BIGSERIAL PRIMARY KEY,
    client_name VARCHAR(120) NOT NULL UNIQUE,
    tally_company_name VARCHAR(150) NOT NULL,
    gstin VARCHAR(15),
    state_code VARCHAR(2) NOT NULL DEFAULT '24',
    default_mode VARCHAR(20) NOT NULL DEFAULT 'SUMMARY', -- 'SUMMARY' or 'ITEM'
    default_purchase_ledger VARCHAR(180) DEFAULT 'Purchase Account',
    default_roundoff_ledger VARCHAR(180) DEFAULT 'Round Off',
    show_gst_columns BOOLEAN NOT NULL DEFAULT TRUE,
    show_hsn_column BOOLEAN NOT NULL DEFAULT TRUE,
    show_cost_center BOOLEAN NOT NULL DEFAULT FALSE,
    show_po_reference BOOLEAN NOT NULL DEFAULT FALSE,
    excel_column_mapping_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 4. Upload Batch Tracker
CREATE TABLE IF NOT EXISTS upload_batches (
    id BIGSERIAL PRIMARY KEY,
    client_profile_id BIGINT REFERENCES client_profiles(id) ON DELETE SET NULL,
    batch_reference VARCHAR(60) NOT NULL UNIQUE,
    total_files INT NOT NULL DEFAULT 0,
    processed_count INT NOT NULL DEFAULT 0,
    api_calls_consumed INT NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING', -- 'PENDING', 'PROCESSING', 'REVIEW_NEEDED', 'COMPLETED'
    created_by VARCHAR(60),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 5. Master Bill Staging Records
CREATE TABLE IF NOT EXISTS bill_records (
    id BIGSERIAL PRIMARY KEY,
    batch_id BIGINT REFERENCES upload_batches(id) ON DELETE CASCADE,
    client_profile_id BIGINT REFERENCES client_profiles(id) ON DELETE CASCADE,
    voucher_mode VARCHAR(20) NOT NULL DEFAULT 'SUMMARY', -- 'SUMMARY', 'ITEM'
    invoice_number VARCHAR(80),
    invoice_date DATE,
    vendor_name_printed VARCHAR(180),
    vendor_gstin VARCHAR(15),
    tally_party_ledger VARCHAR(180),
    tally_purchase_ledger VARCHAR(180),
    cost_center VARCHAR(120),
    po_reference VARCHAR(80),
    taxable_amount NUMERIC(15, 2) NOT NULL DEFAULT 0.00,
    cgst_amount NUMERIC(15, 2) NOT NULL DEFAULT 0.00,
    sgst_amount NUMERIC(15, 2) NOT NULL DEFAULT 0.00,
    igst_amount NUMERIC(15, 2) NOT NULL DEFAULT 0.00,
    round_off NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    grand_total NUMERIC(15, 2) NOT NULL DEFAULT 0.00,
    accuracy_percentage NUMERIC(5, 2) NOT NULL DEFAULT 0.00,
    validation_notes TEXT,
    attached_file_paths TEXT, -- JSON array of source page images
    status VARCHAR(30) NOT NULL DEFAULT 'EXTRACTED', -- 'EXTRACTED', 'VERIFIED', 'EXPORTED_TALLY', 'FAILED'
    tally_voucher_guid VARCHAR(120),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 6. Child Bill Line Items
CREATE TABLE IF NOT EXISTS bill_line_items (
    id BIGSERIAL PRIMARY KEY,
    bill_record_id BIGINT REFERENCES bill_records(id) ON DELETE CASCADE,
    line_number INT NOT NULL,
    extracted_item_name VARCHAR(255) NOT NULL,
    mapped_tally_stock_item VARCHAR(255),
    hsn_sac VARCHAR(20),
    quantity NUMERIC(12, 4) NOT NULL DEFAULT 1.0000,
    uom VARCHAR(20) DEFAULT 'NOS',
    rate NUMERIC(15, 4) NOT NULL DEFAULT 0.0000,
    discount_amount NUMERIC(15, 2) NOT NULL DEFAULT 0.00,
    tax_rate NUMERIC(5, 2) NOT NULL DEFAULT 0.00,
    taxable_amount NUMERIC(15, 2) NOT NULL DEFAULT 0.00,
    line_accuracy_percentage NUMERIC(5, 2) NOT NULL DEFAULT 100.00,
    line_error_notes VARCHAR(255)
);

-- 7. Auto-Learning Tally Mapping Cache
CREATE TABLE IF NOT EXISTS tally_mapping_cache (
    id BIGSERIAL PRIMARY KEY,
    client_profile_id BIGINT REFERENCES client_profiles(id) ON DELETE CASCADE,
    mapping_type VARCHAR(30) NOT NULL, -- 'VENDOR_TO_LEDGER', 'ITEM_TO_STOCK'
    source_identifier VARCHAR(255) NOT NULL, -- Printed vendor name or GSTIN / Printed item description
    tally_target_name VARCHAR(255) NOT NULL, -- Tally ledger name or Tally stock item name
    tally_secondary_name VARCHAR(100),       -- Default UOM or Purchase Ledger
    confidence_hits INT NOT NULL DEFAULT 1,
    last_applied_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_client_mapping UNIQUE (client_profile_id, mapping_type, source_identifier)
);

CREATE INDEX IF NOT EXISTS idx_bill_records_batch ON bill_records(batch_id);
CREATE INDEX IF NOT EXISTS idx_bill_line_items_record ON bill_line_items(bill_record_id);
CREATE INDEX IF NOT EXISTS idx_tally_cache_lookup ON tally_mapping_cache(client_profile_id, mapping_type, source_identifier);
