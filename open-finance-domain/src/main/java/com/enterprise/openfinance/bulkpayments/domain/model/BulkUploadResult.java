package com.enterprise.openfinance.bulkpayments.domain.model;

import java.time.Instant;

public record BulkUploadResult(
        String fileId,
        BulkFileStatus status,
        String interactionId,
        boolean idempotencyReplay,
        int acceptedCount,
        int rejectedCount,
        Instant createdAt
) {

    public BulkUploadResult {
        if (isBlank(fileId)) {
            throw new IllegalArgumentException("fileId is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (isBlank(interactionId)) {
            throw new IllegalArgumentException("interactionId is required");
        }
        if (acceptedCount < 0) {
            throw new IllegalArgumentException("acceptedCount must be >= 0");
        }
        if (rejectedCount < 0) {
            throw new IllegalArgumentException("rejectedCount must be >= 0");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt is required");
        }

        fileId = fileId.trim();
        interactionId = interactionId.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
