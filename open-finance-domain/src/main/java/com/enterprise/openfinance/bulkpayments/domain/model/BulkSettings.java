package com.enterprise.openfinance.bulkpayments.domain.model;

import java.time.Duration;

/**
 * Policy values of the bulk-payments context.
 *
 * Idempotency keys have no time to live: a key always answers with its
 * original file (see BulkIdempotencyRecord).
 *
 * @param processingBatchSize upper bound of items processed per transaction,
 *                            so a large file never holds one long transaction
 */
public record BulkSettings(
        Duration cacheTtl,
        long maxFileSizeBytes,
        int processingBatchSize
) {

    public BulkSettings {
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
