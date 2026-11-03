-- V11_0_34: Consolidate WhatsApp Category and Cleanup Lookup Master
-- Ensure single canonical WhatsApp category and eliminate duplicate categories

-- 1. Remove duplicate/differently cased WhatsApp categories from master_category
DELETE FROM master_category 
WHERE UPPER(TRIM(REPLACE(category_code, ' ', '_'))) IN ('WHATSAPP_SENDER', 'WHATSAPP_SENDERS', 'WHATSAPP')
   OR UPPER(TRIM(REPLACE(category_name, ' ', '_'))) IN ('WHATSAPP_SENDER', 'WHATSAPP_SENDERS', 'WHATSAPP')
   OR UPPER(category_code) LIKE '%WHATSAPP%'
   OR UPPER(category_name) LIKE '%WHATSAPP%';

-- 2. Insert single canonical master_category
INSERT INTO master_category (category_code, category_name, description, display_order, is_active, row_version)
VALUES ('WHATSAPP_SENDER', 'WhatsApp', 'WhatsApp sender numbers used for automated customer dispatch and system storage risk alerts', 150, 1, 0);

-- 3. Standardize lookup_type across all lookup_master entries for WhatsApp to 'WhatsApp'
UPDATE lookup_master 
SET lookup_type = 'WhatsApp',
    row_version = row_version + 1
WHERE UPPER(REPLACE(lookup_type, ' ', '_')) IN ('WHATSAPP_SENDER', 'WHATSAPP_SENDERS', 'WHATSAPP')
   OR UPPER(lookup_type) LIKE '%WHATSAPP%';

-- 4. Deactivate dummy placeholder test numbers so only verified user numbers appear
UPDATE lookup_master 
SET is_active = 0,
    row_version = row_version + 1
WHERE UPPER(TRIM(lookup_code)) = 'PRIMARY_SENDER' 
  AND TRIM(lookup_value) = '9099939193';
