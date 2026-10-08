-- A PARKED row keeps the rest of its aggregate (bulk file) blocked until it is replayed
-- (ADR-021 decision 4); the relay's pending query checks for one per aggregate.
CREATE INDEX ix_outbox_parked_aggregate ON outbox_event (aggregate_id) WHERE status = 'PARKED';
