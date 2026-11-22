-- DSE ERP 11.0.77: Granular notification recipient preferences for party contacts
-- Enables Customer 360° and Supplier 360° to configure multiple auto-populated
-- recipients for PDF email transmissions and payment advice WhatsApp notifications.

ALTER TABLE party_contact 
    ADD COLUMN IF NOT EXISTS is_email_recipient BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS is_whatsapp_recipient BOOLEAN NOT NULL DEFAULT FALSE;

-- Automatically backfill existing primary contacts so current live workflows remain uninterrupted
UPDATE party_contact 
SET is_email_recipient = TRUE, is_whatsapp_recipient = TRUE 
WHERE is_primary = TRUE;

CREATE INDEX IF NOT EXISTS idx_party_contact_notifications 
    ON party_contact(party_id, is_email_recipient, is_whatsapp_recipient);
