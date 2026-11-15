-- V11.0.68: Add explicit whatsapp_status tracking to payment_record and return_refund
ALTER TABLE payment_record ADD COLUMN IF NOT EXISTS whatsapp_status VARCHAR(30) DEFAULT 'PENDING';
UPDATE payment_record p
SET whatsapp_status = 'SENT'
WHERE EXISTS (
    SELECT 1 FROM communication_log c
    WHERE c.entity_type = p.document_type
      AND c.entity_id = p.document_id
      AND c.channel = 'WHATSAPP'
      AND c.status = 'SENT'
);

ALTER TABLE return_refund ADD COLUMN IF NOT EXISTS whatsapp_status VARCHAR(30) DEFAULT 'PENDING';
UPDATE return_refund r
SET whatsapp_status = 'SENT'
WHERE EXISTS (
    SELECT 1 FROM communication_log c
    WHERE c.channel = 'WHATSAPP'
      AND c.status = 'SENT'
      AND c.subject LIKE '%' || r.return_no || '%'
);

CREATE INDEX IF NOT EXISTS ix_payment_record_whatsapp_status ON payment_record(whatsapp_status);
CREATE INDEX IF NOT EXISTS ix_return_refund_whatsapp_status ON return_refund(whatsapp_status);
