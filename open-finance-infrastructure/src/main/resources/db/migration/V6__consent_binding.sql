-- A bulk payment consent authorises exactly one file. The consent read from the consent
-- service carries no file limits, so this service records what each consent authorised:
-- the file, its hash, item count and control sum. consent_id is the primary key, so a
-- second file on the same consent (also a concurrent one) cannot be committed; file_id is
-- unique, so one file never consumes two consents. The FK is deferred because the binding
-- is written before the file row in the same transaction.
CREATE TABLE bulk_consent_binding (
    consent_id   VARCHAR(128)   NOT NULL,
    tpp_id       VARCHAR(128)   NOT NULL,
    file_id      VARCHAR(64)    NOT NULL,
    file_hash    VARCHAR(128)   NOT NULL,
    item_count   INTEGER        NOT NULL,
    control_sum  NUMERIC(19, 4) NOT NULL,
    currency     VARCHAR(3)     NOT NULL,
    bound_at     TIMESTAMPTZ    NOT NULL,
    CONSTRAINT pk_bulk_consent_binding PRIMARY KEY (consent_id),
    CONSTRAINT uq_bulk_consent_binding_file UNIQUE (file_id),
    CONSTRAINT fk_bulk_consent_binding_file FOREIGN KEY (file_id) REFERENCES bulk_file (file_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_bulk_consent_binding_items CHECK (item_count > 0),
    CONSTRAINT ck_bulk_consent_binding_sum CHECK (control_sum > 0),
    CONSTRAINT ck_bulk_consent_binding_currency CHECK (currency ~ '^[A-Z]{3}$')
);
