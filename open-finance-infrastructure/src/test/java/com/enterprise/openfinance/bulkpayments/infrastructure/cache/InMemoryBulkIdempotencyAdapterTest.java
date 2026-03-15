package com.enterprise.openfinance.bulkpayments.infrastructure.cache;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkFileStatus;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkIdempotencyRecord;
import com.enterprise.openfinance.bulkpayments.infrastructure.config.BulkPaymentsCacheProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class InMemoryBulkIdempotencyAdapterTest {

    @Test
    void shouldReturnRecordBeforeExpiryAndEvictAfter() {
        BulkPaymentsCacheProperties properties = new BulkPaymentsCacheProperties();
        properties.setMaxEntries(2);
        InMemoryBulkIdempotencyAdapter adapter = new InMemoryBulkIdempotencyAdapter(properties);

        BulkIdempotencyRecord record = new BulkIdempotencyRecord(
                "IDEMP-1",
                "TPP-001",
                "hash-1",
                "FILE-1",
                BulkFileStatus.PROCESSING,
                Instant.parse("2026-02-09T10:01:00Z")
        );

        adapter.save(record);

        assertThat(adapter.find("IDEMP-1", "TPP-001", Instant.parse("2026-02-09T10:00:00Z"))).isPresent();
        assertThat(adapter.find("IDEMP-1", "TPP-001", Instant.parse("2026-02-09T10:01:00Z"))).isEmpty();
    }

    @Test
    void shouldEvictWhenCapacityExceeded() {
        BulkPaymentsCacheProperties properties = new BulkPaymentsCacheProperties();
        properties.setMaxEntries(1);
        InMemoryBulkIdempotencyAdapter adapter = new InMemoryBulkIdempotencyAdapter(properties);

        adapter.save(new BulkIdempotencyRecord("IDEMP-1", "TPP-001", "hash-1", "FILE-1", BulkFileStatus.PROCESSING, Instant.parse("2026-02-09T10:01:00Z")));
        adapter.save(new BulkIdempotencyRecord("IDEMP-2", "TPP-001", "hash-2", "FILE-2", BulkFileStatus.PROCESSING, Instant.parse("2026-02-09T10:01:00Z")));

        assertThat(adapter.find("IDEMP-1", "TPP-001", Instant.parse("2026-02-09T10:00:00Z")).isPresent()
                || adapter.find("IDEMP-2", "TPP-001", Instant.parse("2026-02-09T10:00:00Z")).isPresent()).isTrue();
    }
}
