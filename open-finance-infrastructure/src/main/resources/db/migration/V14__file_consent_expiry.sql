-- The consent's expiry as read from the consent service at upload. A retry of the
-- upload (same idempotency key and body) after that instant is refused with
-- the uniform 403 (ADR-025 item 5) before the replay, where the monolith answered "Consent expired"; decided
-- locally with no consent-service read. Nullable: files stored before V14 have
-- no recorded expiry and keep being replayed as before.
ALTER TABLE bulk_file ADD COLUMN consent_expires_at TIMESTAMPTZ;

COMMENT ON COLUMN bulk_file.consent_expires_at IS 'Consent expiry read at upload; a retry after it gets the uniform 403 (ADR-025 item 5). NULL for files stored before V14.';
