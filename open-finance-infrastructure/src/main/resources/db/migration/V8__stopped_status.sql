-- STOPPED: the consent stopped being usable (revoked, expired, gone or out of scope) while the
-- file was processing; the remaining items are never released. processed_at is when it stopped.
ALTER TABLE bulk_file DROP CONSTRAINT ck_bulk_file_status;
ALTER TABLE bulk_file DROP CONSTRAINT ck_bulk_file_terminal;

ALTER TABLE bulk_file ADD CONSTRAINT ck_bulk_file_status
    CHECK (status IN ('PROCESSING', 'VALIDATED', 'REJECTED', 'STOPPED'));
ALTER TABLE bulk_file ADD CONSTRAINT ck_bulk_file_terminal CHECK (
    (status = 'PROCESSING' AND processed_count < total_count)
    OR (status = 'STOPPED' AND processed_count < total_count AND processed_at IS NOT NULL)
    OR (status = target_status AND processed_count = total_count AND processed_at IS NOT NULL));
