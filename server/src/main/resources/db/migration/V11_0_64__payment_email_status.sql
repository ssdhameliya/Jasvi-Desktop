-- V11.0.64: Add explicit email_status tracking to payment_record and return_refund
ALTER TABLE payment_record ADD COLUMN IF NOT EXISTS email_status VARCHAR(30) DEFAULT 'PENDING';
UPDATE payment_record p
SET email_status = 'SENT'
WHERE EXISTS (
    SELECT 1 FROM communication_log c
    WHERE c.entity_type = p.document_type
      AND c.entity_id = p.document_id
      AND c.channel = 'EMAIL'
      AND c.status = 'SENT'
);

ALTER TABLE return_refund ADD COLUMN IF NOT EXISTS email_status VARCHAR(30) DEFAULT 'PENDING';
UPDATE return_refund r
SET email_status = 'SENT'
WHERE EXISTS (
    SELECT 1 FROM communication_log c
    WHERE c.channel = 'EMAIL'
      AND c.status = 'SENT'
      AND c.subject LIKE '%' || r.return_no || '%'
);

CREATE INDEX IF NOT EXISTS ix_payment_record_email_status ON payment_record(email_status);
CREATE INDEX IF NOT EXISTS ix_return_refund_email_status ON return_refund(email_status);
