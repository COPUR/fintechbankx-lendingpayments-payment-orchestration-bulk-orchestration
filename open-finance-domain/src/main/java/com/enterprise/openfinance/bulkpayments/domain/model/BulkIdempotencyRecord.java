package com.enterprise.openfinance.bulkpayments.domain.model;

import java.time.Instant;

public record BulkIdempotencyRecord(
        String idempotencyKey,
        String tppId,
        String requestHash,
        String fileId,
        BulkFileStatus status,
        Instant expiresAt
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
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt is required");
        }

        idempotencyKey = idempotencyKey.trim();
        tppId = tppId.trim();
        requestHash = requestHash.trim();
        fileId = fileId.trim();
    }

    public boolean isActive(Instant now) {
        return expiresAt.isAfter(now);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
