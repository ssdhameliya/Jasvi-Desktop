-- DSE ERP 11.0.30: Clean and Consolidate WhatsApp Master Category & Lookups
-- Eliminates duplicate WhatsApp categories so only the canonical WHATSAPP_SENDER category exists.

-- 1. Ensure any lookups referencing legacy WhatsApp category variants point to WHATSAPP_SENDER
UPDATE lookup_master
SET lookup_type = 'WHATSAPP_SENDER'
WHERE UPPER(lookup_type) LIKE '%WHATSAPP%'
  AND lookup_type <> 'WHATSAPP_SENDER';

-- 2. Delete any duplicate categories in master_category except canonical 'WHATSAPP_SENDER'
DELETE FROM master_category
WHERE (UPPER(category_code) LIKE '%WHATSAPP%' OR UPPER(category_name) LIKE '%WHATSAPP%')
  AND category_code <> 'WHATSAPP_SENDER';

-- 3. Upsert the canonical WHATSAPP_SENDER category
INSERT INTO master_category (category_code, category_name, description, display_order, is_active)
VALUES (
    'WHATSAPP_SENDER',
    'WhatsApp Senders',
    'Sender & Alert WhatsApp numbers used for automated customer dispatch and system storage risk alerts',
    150,
    1
)
ON CONFLICT (category_code) DO UPDATE
SET category_name = EXCLUDED.category_name,
    description = EXCLUDED.description,
    is_active = 1;

-- 4. Clean duplicate lookup entries in lookup_master for WHATSAPP_SENDER (preserve lowest id)
DELETE FROM lookup_master a
USING lookup_master b
WHERE a.id > b.id
  AND a.lookup_type = 'WHATSAPP_SENDER'
  AND b.lookup_type = 'WHATSAPP_SENDER'
  AND (
      (a.lookup_code IS NOT NULL AND b.lookup_code IS NOT NULL AND BTRIM(UPPER(a.lookup_code)) = BTRIM(UPPER(b.lookup_code)))
      OR (a.lookup_value IS NOT NULL AND b.lookup_value IS NOT NULL AND REGEXP_REPLACE(a.lookup_value, '[^0-9]', '', 'g') <> '' AND REGEXP_REPLACE(a.lookup_value, '[^0-9]', '', 'g') = REGEXP_REPLACE(b.lookup_value, '[^0-9]', '', 'g'))
  );
