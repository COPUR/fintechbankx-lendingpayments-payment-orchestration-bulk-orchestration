package com.enterprise.openfinance.bulkpayments.domain.port.out;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkIdempotencyRecord;

import java.time.Instant;
import java.util.Optional;

public interface BulkIdempotencyPort {

    /** The record for this key and TPP, if it has not expired at {@code now}. */
    Optional<BulkIdempotencyRecord> find(String idempotencyKey, String tppId, Instant now);

    /**
     * Stores the record unless an unexpired record holds the same key for the
     * same TPP. Atomic: of two concurrent uploads with one key, only one wins.
     *
     * @return {@code true} if this call reserved the key
     */
    boolean reserve(BulkIdempotencyRecord record, Instant now);
}
