package com.enterprise.openfinance.bulkpayments.domain.port.out;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkIdempotencyRecord;

import java.time.Instant;
import java.util.Optional;

public interface BulkIdempotencyPort {
    Optional<BulkIdempotencyRecord> find(String idempotencyKey, String tppId, Instant now);

    void save(BulkIdempotencyRecord record);
}
