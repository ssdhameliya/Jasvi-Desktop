-- DSE ERP 11.0.35: Consolidate WhatsApp Category and Cleanup Lookup Master
-- Ensure single canonical WhatsApp category in master_category and lookup_master

-- 1. Ensure only ONE master_category entry exists for WhatsApp
DELETE FROM master_category 
WHERE UPPER(TRIM(REPLACE(category_code, ' ', '_'))) IN ('WHATSAPP_SENDERS', 'WHATSAPP')
   OR (UPPER(TRIM(REPLACE(category_code, ' ', '_'))) = 'WHATSAPP_SENDER' AND category_code <> 'WHATSAPP_SENDER')
   OR UPPER(category_name) = 'WHATSAPP SENDERS';

-- Ensure WHATSAPP_SENDER exists with display name 'WhatsApp Senders'
INSERT INTO master_category (category_code, category_name, description, display_order, is_active, row_version)
VALUES ('WHATSAPP_SENDER', 'WhatsApp Senders', 'WhatsApp sender numbers used for automated customer dispatch and system storage risk alerts', 150, 1, 0)
ON CONFLICT (category_code) DO UPDATE
SET category_name = 'WhatsApp Senders',
    description = 'WhatsApp sender numbers used for automated customer dispatch and system storage risk alerts',
    is_active = 1;

-- 2. Standardize lookup_master lookup_type to 'WHATSAPP_SENDER'
UPDATE lookup_master 
SET lookup_type = 'WHATSAPP_SENDER',
    row_version = row_version + 1
WHERE UPPER(REPLACE(lookup_type, ' ', '_')) IN ('WHATSAPP_SENDER', 'WHATSAPP_SENDERS', 'WHATSAPP')
   OR UPPER(lookup_type) LIKE '%WHATSAPP%';

-- 3. Deactivate any placeholder numbers with 9099939193 so only verified user numbers appear
UPDATE lookup_master 
SET is_active = 0,
    row_version = row_version + 1
WHERE lookup_value = '9099939193' OR lookup_code = 'PRIMARY_SENDER';
