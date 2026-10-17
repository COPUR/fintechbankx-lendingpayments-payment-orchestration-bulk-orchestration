package com.enterprise.openfinance.bulkpayments.domain.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * No item of the file will be released: either every item was rejected at validation,
 * or the consent stopped being usable while the file was processing.
 */
public record BulkFileRejected(
        UUID eventId,
        String fileId,
        long aggregateVersion,
        Instant occurredAt,
        int totalCount,
        int rejectedCount,
        Reason reason
) implements BulkFileEvent {

    public enum Reason {
        ALL_ITEMS_REJECTED,
        CONSENT_NOT_USABLE
    }

    public BulkFileRejected {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(fileId, "fileId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(reason, "reason");
    }
}
