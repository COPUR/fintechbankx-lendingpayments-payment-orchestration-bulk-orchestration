-- ADR-019 (owner decision 2026-10-08): one topic per aggregate. Every bulk
-- file event goes to evt.pay.bulk.v1, keyed by the file id, with the eventType
-- record header naming the event. OutboxRelay computes the topic, so new rows
-- no longer store one.
--
-- Expand only: topic becomes nullable and is no longer written. Rows written
-- before V15 keep their old per-event value, which the relay ignores. The
-- column stays so that a rollback to the previous release can still insert;
-- a later migration drops it once that rollback window has closed.

ALTER TABLE outbox_event ALTER COLUMN topic DROP NOT NULL;

COMMENT ON COLUMN outbox_event.topic IS 'Deprecated since V15, not written: the relay sends every row to evt.pay.bulk.v1. Dropped in a later migration.';
