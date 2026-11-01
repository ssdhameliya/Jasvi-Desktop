-- DSE ERP 11.0.28: WhatsApp Sender & Alert Multi-Number Master Lookup
-- Ensures WHATSAPP_SENDER category exists in master_category and auto-seeds company phone if not present.

INSERT INTO master_category (category_code, category_name, description, display_order, is_active)
VALUES (
    'WHATSAPP_SENDER',
    'WhatsApp Senders',
    'Sender & Alert WhatsApp numbers used for automated customer dispatch and system storage risk alerts',
    150,
    1
)
ON CONFLICT (category_code) DO UPDATE
SET description = EXCLUDED.description,
    is_active = 1;

-- Auto-seed the first active lookup value from company.phone if none exists yet in WHATSAPP_SENDER
INSERT INTO lookup_master (lookup_type, lookup_code, lookup_value, description, display_order, is_active)
SELECT 
    'WHATSAPP_SENDER',
    'PRIMARY_SENDER',
    setting_value,
    'Primary Company WhatsApp Number',
    1,
    1
FROM application_setting
WHERE setting_key IN ('company.phone', 'company_phone')
  AND setting_value IS NOT NULL 
  AND BTRIM(setting_value) <> ''
  AND NOT EXISTS (
      SELECT 1 FROM lookup_master WHERE UPPER(lookup_type) = 'WHATSAPP_SENDER'
  )
LIMIT 1;
