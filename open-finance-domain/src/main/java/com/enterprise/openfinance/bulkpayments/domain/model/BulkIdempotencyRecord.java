package com.enterprise.openfinance.bulkpayments.domain.model;

/**
 * An upload's idempotency key, per TPP. Permanent: a key never expires and is
 * never reusable; it answers with its original file for good.
 */
public record BulkIdempotencyRecord(
        String idempotencyKey,
        String tppId,
        String requestHash,
        String fileId,
        BulkFileStatus status
) {

    public BulkIdempotencyRecord {
        if (isBlank(idempotencyKey)) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        if (isBlank(tppId)) {
            throw new IllegalArgumentException("tppId is required");
        }
        if (isBlank(requestHash)) {
            throw new IllegalArgumentException("requestHash is required");
        }
        if (isBlank(fileId)) {
            throw new IllegalArgumentException("fileId is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }

        idempotencyKey = idempotencyKey.trim();
        tppId = tppId.trim();
        requestHash = requestHash.trim();
        fileId = fileId.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
