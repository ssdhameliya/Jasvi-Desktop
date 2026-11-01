-- DSE ERP 10.0.38: WhatsApp WAHA Integration Schema & Channel Configuration
-- Adds dedicated multi-number WhatsApp sender channels table and application settings.

CREATE TABLE IF NOT EXISTS whatsapp_sender_channel (
    id BIGSERIAL PRIMARY KEY,
    channel_code TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    phone_number TEXT NOT NULL,
    session_name TEXT NOT NULL DEFAULT 'default',
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    connection_status TEXT NOT NULL DEFAULT 'DISCONNECTED',
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_whatsapp_channel_active ON whatsapp_sender_channel (is_active, is_default);

-- Master category for WhatsApp Sender channels
INSERT INTO master_category (category_code, category_name, display_order, is_active)
VALUES ('WHATSAPP_SENDER', 'WhatsApp Senders', 150, 1)
ON CONFLICT (category_code) DO NOTHING;

-- Default Application Settings for WAHA
INSERT INTO application_setting (setting_key, setting_value, updated_at)
VALUES 
    ('whatsapp.waha.endpoint', 'http://localhost:3000', CURRENT_TIMESTAMP::text),
    ('whatsapp.waha.api_key', '', CURRENT_TIMESTAMP::text),
    ('whatsapp.enabled', 'true', CURRENT_TIMESTAMP::text),
    ('whatsapp.default_channel_id', '1', CURRENT_TIMESTAMP::text)
ON CONFLICT (setting_key) DO NOTHING;

-- Seed default primary channel if table is empty
INSERT INTO whatsapp_sender_channel (channel_code, display_name, phone_number, session_name, is_default, is_active, connection_status)
VALUES ('PRIMARY_WHATSAPP', 'Primary Company WhatsApp', '', 'default', true, true, 'DISCONNECTED')
ON CONFLICT (channel_code) DO NOTHING;
