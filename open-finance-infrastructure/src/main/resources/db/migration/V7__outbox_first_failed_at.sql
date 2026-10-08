-- Retryable send failures (broker, DNS or mesh-egress outages) no longer count
-- toward parking; the relay.max-attempts cap is removed. A row is parked on a
-- retryable failure only after it has been failing continuously for longer
-- than openfinance.bulkpayments.outbox.relay.retryable-park-after (default 24 h),
-- measured from first_failed_at. Non-retriable failures still park at once.
-- Same column as svc-rsk-decisioning and compliance-evidence-core.

ALTER TABLE outbox_event ADD COLUMN first_failed_at TIMESTAMPTZ;

COMMENT ON COLUMN outbox_event.first_failed_at IS 'First failed send of the row; reset to NULL with parked_at on a manual replay.';
