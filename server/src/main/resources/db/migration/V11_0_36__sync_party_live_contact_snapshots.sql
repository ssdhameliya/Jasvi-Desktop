-- V11_0_36__sync_party_live_contact_snapshots.sql
-- Synchronize existing sales and purchase contact snapshots with live customer and supplier contacts

UPDATE sales_header sh
SET customer_phone_snapshot = p.phone,
    customer_email_snapshot = p.email
FROM party_master p
WHERE sh.customer_id = p.id
  AND p.phone IS NOT NULL
  AND p.phone <> '';

UPDATE purchase_header ph
SET supplier_phone_snapshot = p.phone,
    supplier_email_snapshot = p.email
FROM party_master p
WHERE ph.supplier_id = p.id
  AND p.phone IS NOT NULL
  AND p.phone <> '';
