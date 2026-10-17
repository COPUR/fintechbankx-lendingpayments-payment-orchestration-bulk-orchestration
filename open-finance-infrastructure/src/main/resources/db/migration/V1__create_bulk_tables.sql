-- svc-pay-bulk-orchestration owns these tables. Schema sc_pay_bulk_orchestration
-- (Flyway runs with it as default schema, so names are unqualified), database
-- db_pay_bulk_orchestration_<env>.
--
-- New tables, not a split: the monolith (enterprise-loan-management-system,
-- open-finance-context bulkpayments) kept bulk files in memory only and never
-- persisted them. No backfill exists or is needed.

CREATE TABLE bulk_file (
    file_id           VARCHAR(64)    PRIMARY KEY,
    consent_id        VARCHAR(128)   NOT NULL,
    tpp_id            VARCHAR(128)   NOT NULL,
    idempotency_key   VARCHAR(160)   NOT NULL,
    request_hash      VARCHAR(512)   NOT NULL,
    file_name         VARCHAR(255)   NOT NULL,
    integrity_mode    VARCHAR(32)    NOT NULL,
    status            VARCHAR(32)    NOT NULL,
    target_status     VARCHAR(32)    NOT NULL,
    processed_count   INTEGER        NOT NULL,
    total_count       INTEGER        NOT NULL,
    accepted_count    INTEGER        NOT NULL,
    rejected_count    INTEGER        NOT NULL,
    total_amount      NUMERIC(19, 4) NOT NULL,
    accepted_amount   NUMERIC(19, 4) NOT NULL,
    created_at        TIMESTAMPTZ    NOT NULL,
    processed_at      TIMESTAMPTZ,
    version           BIGINT         NOT NULL,

    CONSTRAINT uq_bulk_file_tpp_idempotency_key UNIQUE (tpp_id, idempotency_key),
    CONSTRAINT ck_bulk_file_status CHECK (status IN ('PROCESSING', 'COMPLETED', 'PARTIALLY_ACCEPTED', 'REJECTED')),
    CONSTRAINT ck_bulk_file_target_status CHECK (target_status IN ('COMPLETED', 'PARTIALLY_ACCEPTED', 'REJECTED')),
    CONSTRAINT ck_bulk_file_integrity_mode CHECK (integrity_mode IN ('PARTIAL_REJECTION', 'FULL_REJECTION')),
    CONSTRAINT ck_bulk_file_counts CHECK (
        total_count > 0
        AND accepted_count >= 0 AND rejected_count >= 0
        AND accepted_count + rejected_count = total_count
        AND processed_count BETWEEN 0 AND total_count),
    CONSTRAINT ck_bulk_file_amounts CHECK (total_amount > 0 AND accepted_amount BETWEEN 0 AND total_amount),
    CONSTRAINT ck_bulk_file_terminal CHECK (
        (status = 'PROCESSING' AND processed_count < total_count)
        OR (status = target_status AND processed_count = total_count AND processed_at IS NOT NULL))
);

-- The processor claims the oldest processing file (FOR UPDATE SKIP LOCKED).
CREATE INDEX ix_bulk_file_processing ON bulk_file (created_at) WHERE status = 'PROCESSING';

-- Items are stored apart from the file so a large file never loads as one row.
CREATE TABLE bulk_item (
    file_id         VARCHAR(64)    NOT NULL REFERENCES bulk_file (file_id) ON DELETE CASCADE,
    line_number     INTEGER        NOT NULL,
    instruction_id  VARCHAR(128)   NOT NULL,
    payee_iban      VARCHAR(64)    NOT NULL,
    amount          NUMERIC(19, 4) NOT NULL,
    status          VARCHAR(16)    NOT NULL,
    error_message   VARCHAR(255),
    processed_at    TIMESTAMPTZ,

    CONSTRAINT pk_bulk_item PRIMARY KEY (file_id, line_number),
    CONSTRAINT ck_bulk_item_line CHECK (line_number > 0),
    CONSTRAINT ck_bulk_item_amount CHECK (amount > 0),
    CONSTRAINT ck_bulk_item_status CHECK (status IN ('ACCEPTED', 'REJECTED'))
);

-- Next batch of a file: unprocessed items in line order.
CREATE INDEX ix_bulk_item_unprocessed ON bulk_item (file_id, line_number) WHERE processed_at IS NULL;

-- Upload idempotency per TPP. The row is reserved before the file is written
-- (INSERT ... ON CONFLICT), so two concurrent uploads with one key cannot both
-- create a file. The FK is checked at commit, after the file row exists.
CREATE TABLE bulk_idempotency (
    tpp_id           VARCHAR(128) NOT NULL,
    idempotency_key  VARCHAR(160) NOT NULL,
    request_hash     VARCHAR(512) NOT NULL,
    file_id          VARCHAR(64)  NOT NULL,
    file_status      VARCHAR(32)  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    expires_at       TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_bulk_idempotency PRIMARY KEY (tpp_id, idempotency_key),
    CONSTRAINT fk_bulk_idempotency_file FOREIGN KEY (file_id) REFERENCES bulk_file (file_id)
        ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_bulk_idempotency_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_bulk_idempotency_expires_at ON bulk_idempotency (expires_at);

COMMENT ON TABLE bulk_file IS 'Bulk payment files (aggregate BulkFile) of svc-pay-bulk-orchestration.';
COMMENT ON TABLE bulk_item IS 'Items of a bulk file with their validation outcome; processed_at is set by the batch processor.';
COMMENT ON TABLE bulk_idempotency IS 'Upload idempotency keys per TPP; expired keys may be reused.';
