-- DSE ERP 11.0.89: Widen bank_statement_transaction.suggested_match_type for Smart Rule tags
ALTER TABLE bank_statement_transaction
    ALTER COLUMN suggested_match_type TYPE VARCHAR(255);
