package com.enterprise.openfinance.bulkpayments.domain.model;

import java.time.Duration;

/**
 * Policy values of the bulk-payments context.
 *
 * @param processingBatchSize upper bound of items processed per transaction,
 *                            so a large file never holds one long transaction
 */
public record BulkSettings(
        Duration idempotencyTtl,
        Duration cacheTtl,
        long maxFileSizeBytes,
        int processingBatchSize
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
        if (processingBatchSize <= 0) {
            throw new IllegalArgumentException("processingBatchSize must be positive");
        }
    }
}
