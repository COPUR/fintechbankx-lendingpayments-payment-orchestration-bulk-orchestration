-- Idempotency keys are permanent and never reusable (df4be23): a key answers
-- with its original file for good. expires_at and the idempotency-ttl setting
-- that filled it suggested otherwise (V1's table comment even said expired
-- keys may be reused), so both go. Rows are removed only with their file
-- (fk_bulk_idempotency_file ON DELETE CASCADE); retention follows bulk_file
-- (runbook, "Data retention").

DROP INDEX IF EXISTS ix_bulk_idempotency_expires_at;
ALTER TABLE bulk_idempotency DROP CONSTRAINT IF EXISTS ck_bulk_idempotency_expiry;
ALTER TABLE bulk_idempotency DROP COLUMN expires_at;

COMMENT ON TABLE bulk_idempotency IS 'Upload idempotency keys per TPP; permanent and never reusable: a key always answers with its original file. Deleted only with that file.';
