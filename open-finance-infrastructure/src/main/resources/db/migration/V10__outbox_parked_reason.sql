-- ADR-021 decision 4 (adr-runbooks #10, e6dd76a): only payload errors park a row automatically
-- (parked_reason PAYLOAD_ERROR); every other failure stops the batch without marking anything,
-- with no time ceiling. An operator may park such a row by hand and must record why.
-- (V7, first_failed_at for a 24 h ceiling, was withdrawn before release and never applied.)
ALTER TABLE outbox_event ADD COLUMN parked_reason VARCHAR(256);

ALTER TABLE outbox_event ADD CONSTRAINT ck_outbox_parked_reason
    CHECK (status <> 'PARKED' OR (parked_at IS NOT NULL AND parked_reason IS NOT NULL AND length(trim(parked_reason)) > 0));

COMMENT ON COLUMN outbox_event.parked_reason IS 'PAYLOAD_ERROR from the relay, or the operator''s reason for a manual park; NULL again on replay.';
