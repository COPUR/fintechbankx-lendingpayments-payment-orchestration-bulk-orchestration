package com.enterprise.openfinance.bulkpayments.domain.model;

import java.time.Duration;

public record BulkSettings(
        Duration idempotencyTtl,
        Duration cacheTtl,
        long maxFileSizeBytes,
        int statusPollsToComplete
) {

    public BulkSettings {
        if (idempotencyTtl == null || idempotencyTtl.isZero() || idempotencyTtl.isNegative()) {
            throw new IllegalArgumentException("idempotencyTtl must be positive");
        }
        if (cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative()) {
            throw new IllegalArgumentException("cacheTtl must be positive");
        }
        if (maxFileSizeBytes <= 0) {
            throw new IllegalArgumentException("maxFileSizeBytes must be positive");
        }
        if (statusPollsToComplete <= 0) {
            throw new IllegalArgumentException("statusPollsToComplete must be positive");
        }
    }
}
