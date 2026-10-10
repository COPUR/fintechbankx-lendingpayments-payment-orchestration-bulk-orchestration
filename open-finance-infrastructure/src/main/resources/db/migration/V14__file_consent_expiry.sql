-- The consent's expiry as read from the consent service at upload. A retry of the
-- upload (same idempotency key and body) after that instant is refused with
-- 403 "Consent expired" before the replay, as the monolith refused it, decided
-- locally with no consent-service read. Nullable: files stored before V14 have
-- no recorded expiry and keep being replayed as before.
ALTER TABLE bulk_file ADD COLUMN consent_expires_at TIMESTAMPTZ;

COMMENT ON COLUMN bulk_file.consent_expires_at IS 'Consent expiry read at upload; a retry after it is 403 Consent expired. NULL for files stored before V14.';
