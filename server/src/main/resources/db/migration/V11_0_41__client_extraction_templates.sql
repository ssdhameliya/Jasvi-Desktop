-- V11_0_41__client_extraction_templates.sql
-- Multi-Format Bill-to-Tally Automation: Versioned Extraction Templates and Live GSTIN Profiles

-- 1. Enhance client_profiles with live GST Portal data fields
ALTER TABLE client_profiles
    ADD COLUMN IF NOT EXISTS trade_name VARCHAR(200),
    ADD COLUMN IF NOT EXISTS legal_name VARCHAR(200),
    ADD COLUMN IF NOT EXISTS address TEXT,
    ADD COLUMN IF NOT EXISTS gst_status VARCHAR(50) DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS taxpayer_type VARCHAR(50) DEFAULT 'REGULAR';

-- 2. Create client_extraction_templates table
CREATE TABLE IF NOT EXISTS client_extraction_templates (
    id BIGSERIAL PRIMARY KEY,
    client_profile_id BIGINT NOT NULL REFERENCES client_profiles(id) ON DELETE CASCADE,
    template_name VARCHAR(150) NOT NULL,
    version_code VARCHAR(30) NOT NULL DEFAULT 'v1',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    is_locked BOOLEAN NOT NULL DEFAULT FALSE,
    vendor_gstin VARCHAR(15),
    matching_keywords TEXT,
    header_fields_json TEXT NOT NULL DEFAULT '[]',
    item_columns_json TEXT NOT NULL DEFAULT '[]',
    sample_file_path TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_client_template_version UNIQUE (client_profile_id, version_code)
);

CREATE INDEX IF NOT EXISTS idx_client_templates_gstin ON client_extraction_templates(vendor_gstin);
CREATE INDEX IF NOT EXISTS idx_client_templates_active ON client_extraction_templates(client_profile_id, is_active);
