-- A file whose items are all validated is VALIDATED, not COMPLETED/PARTIALLY_ACCEPTED: no item
-- reaches initiation-settlement yet, so this service does not claim completion.
-- Files from before this change (pre-release only) are mapped onto the new values.
ALTER TABLE bulk_file DROP CONSTRAINT ck_bulk_file_status;
ALTER TABLE bulk_file DROP CONSTRAINT ck_bulk_file_target_status;
ALTER TABLE bulk_file DROP CONSTRAINT ck_bulk_file_terminal;

UPDATE bulk_file SET status = 'VALIDATED' WHERE status IN ('COMPLETED', 'PARTIALLY_ACCEPTED');
UPDATE bulk_file SET target_status = 'VALIDATED' WHERE target_status IN ('COMPLETED', 'PARTIALLY_ACCEPTED');
UPDATE bulk_idempotency SET file_status = 'VALIDATED' WHERE file_status IN ('COMPLETED', 'PARTIALLY_ACCEPTED');

ALTER TABLE bulk_file ADD CONSTRAINT ck_bulk_file_status
    CHECK (status IN ('PROCESSING', 'VALIDATED', 'REJECTED'));
ALTER TABLE bulk_file ADD CONSTRAINT ck_bulk_file_target_status
    CHECK (target_status IN ('VALIDATED', 'REJECTED'));
ALTER TABLE bulk_file ADD CONSTRAINT ck_bulk_file_terminal CHECK (
    (status = 'PROCESSING' AND processed_count < total_count)
    OR (status = target_status AND processed_count = total_count AND processed_at IS NOT NULL));
