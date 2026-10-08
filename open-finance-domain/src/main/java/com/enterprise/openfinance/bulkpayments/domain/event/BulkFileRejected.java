package com.enterprise.openfinance.bulkpayments.domain.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Every item of the file was processed and none was accepted. */
public record BulkFileRejected(
        UUID eventId,
        String fileId,
        long aggregateVersion,
        Instant occurredAt,
        int totalCount,
        int rejectedCount
) implements BulkFileEvent {

    public BulkFileRejected {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(fileId, "fileId");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
