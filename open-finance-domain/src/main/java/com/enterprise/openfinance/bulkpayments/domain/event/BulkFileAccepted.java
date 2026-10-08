package com.enterprise.openfinance.bulkpayments.domain.event;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkIntegrityMode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** The file passed upload checks and its items were stored for processing. */
public record BulkFileAccepted(
        UUID eventId,
        String fileId,
        long aggregateVersion,
        Instant occurredAt,
        String consentId,
        String tppId,
        BulkIntegrityMode integrityMode,
        int totalCount,
        int acceptedCount,
        int rejectedCount,
        BigDecimal totalAmount
) implements BulkFileEvent {

    public BulkFileAccepted {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(fileId, "fileId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(integrityMode, "integrityMode");
        Objects.requireNonNull(totalAmount, "totalAmount");
    }
}
