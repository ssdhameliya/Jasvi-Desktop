-- DSE ERP 11.0.50: WhatsApp Department Channel Routing Schema
-- Provides central configuration keys for routing Sales, Purchase, and Quotation documents to dedicated WhatsApp numbers.

INSERT INTO application_setting (setting_key, setting_value, updated_at)
VALUES 
    ('whatsapp.channel.default_id', '0', CURRENT_TIMESTAMP),
    ('whatsapp.channel.sales_id', '0', CURRENT_TIMESTAMP),
    ('whatsapp.channel.purchase_id', '0', CURRENT_TIMESTAMP),
    ('whatsapp.channel.quotation_id', '0', CURRENT_TIMESTAMP)
ON CONFLICT (setting_key) DO NOTHING;
