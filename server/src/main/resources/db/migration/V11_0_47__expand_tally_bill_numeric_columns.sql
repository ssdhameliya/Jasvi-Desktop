-- ============================================================================
-- DSE ERP 11.0.47: Expand Tally Bill Records & Line Items Numeric Precision
-- ============================================================================

-- 1. Expand tax_rate and line_accuracy_percentage to NUMERIC(10, 2) in bill_line_items
ALTER TABLE bill_line_items ALTER COLUMN tax_rate TYPE NUMERIC(10, 2);
ALTER TABLE bill_line_items ALTER COLUMN line_accuracy_percentage TYPE NUMERIC(10, 2);

-- 2. Expand accuracy_percentage to NUMERIC(10, 2) in bill_records
ALTER TABLE bill_records ALTER COLUMN accuracy_percentage TYPE NUMERIC(10, 2);
