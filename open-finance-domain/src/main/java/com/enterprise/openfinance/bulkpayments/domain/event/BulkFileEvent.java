package com.enterprise.openfinance.bulkpayments.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Facts raised by the {@code BulkFile} aggregate. Infrastructure turns them into
 * the public envelope of the {@code evt.pay.bulk.*.v1} topics.
 */
public sealed interface BulkFileEvent permits BulkFileAccepted, BulkFileRejected {

    UUID eventId();

    String fileId();

    /** Version of the aggregate after the change that raised this event. */
    long aggregateVersion();

    Instant occurredAt();
}
