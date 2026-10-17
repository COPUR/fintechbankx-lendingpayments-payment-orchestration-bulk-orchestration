-- Platform Kafka guide 5f7d546: counter outbox.parked.events
-- (outbox_parked_events_total{exception}) rises once per parked row, for the
-- relay's own payload parks and for operator parks done with the runbook SQL.
-- The relay sets park_counted when it parks a row; operator parks leave it
-- false and the relay (holding the relay lock, so one replica) counts them
-- once on its next run. Rows parked before V12 count as already counted.
-- The runtime role already has UPDATE on outbox_event (V11).

ALTER TABLE outbox_event ADD COLUMN park_counted BOOLEAN NOT NULL DEFAULT false;

UPDATE outbox_event SET park_counted = true WHERE status = 'PARKED';

CREATE INDEX ix_outbox_park_uncounted ON outbox_event (created_seq)
    WHERE status = 'PARKED' AND NOT park_counted;

COMMENT ON COLUMN outbox_event.park_counted IS 'True once the park was counted in outbox_parked_events_total; reset to false on a replay.';
