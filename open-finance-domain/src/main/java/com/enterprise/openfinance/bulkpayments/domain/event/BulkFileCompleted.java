package com.enterprise.openfinance.bulkpayments.domain.event;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkFileStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Every item of the file was processed and at least one was accepted. The
 * outcome is {@link BulkFileStatus#COMPLETED} or {@link BulkFileStatus#PARTIALLY_ACCEPTED}.
 */
public record BulkFileCompleted(
        UUID eventId,
        String fileId,
        long aggregateVersion,
        Instant occurredAt,
        BulkFileStatus outcome,
        int totalCount,
        int acceptedCount,
        int rejectedCount,
        BigDecimal acceptedAmount
) implements BulkFileEvent {

    public BulkFileCompleted {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(fileId, "fileId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(acceptedAmount, "acceptedAmount");
        if (outcome != BulkFileStatus.COMPLETED && outcome != BulkFileStatus.PARTIALLY_ACCEPTED) {
            throw new IllegalArgumentException("outcome must be COMPLETED or PARTIALLY_ACCEPTED");
        }
    }
}
