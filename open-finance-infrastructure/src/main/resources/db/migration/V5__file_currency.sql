-- Every file is in one ISO 4217 currency, supplied on the request (the monolith CSV
-- instruction_id,payee_iban,amount has no currency column). The currency is never defaulted:
-- if pre-release rows exist this migration fails instead of inventing a currency for them.
ALTER TABLE bulk_file ADD COLUMN currency VARCHAR(3) NOT NULL;
ALTER TABLE bulk_file ADD CONSTRAINT ck_bulk_file_currency CHECK (currency ~ '^[A-Z]{3}$');
