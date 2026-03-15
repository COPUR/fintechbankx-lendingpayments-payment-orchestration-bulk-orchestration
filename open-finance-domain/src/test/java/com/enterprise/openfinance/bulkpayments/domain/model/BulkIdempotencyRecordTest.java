package com.enterprise.openfinance.bulkpayments.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BulkIdempotencyRecordTest {

    @Test
    void shouldDetectActiveState() {
        BulkIdempotencyRecord record = new BulkIdempotencyRecord(
                "IDEMP-001",
                "TPP-001",
                "hash-1",
                "FILE-001",
                BulkFileStatus.PROCESSING,
                Instant.parse("2026-02-10T00:00:00Z")
        );

        assertThat(record.isActive(Instant.parse("2026-02-09T00:00:00Z"))).isTrue();
        assertThat(record.isActive(Instant.parse("2026-02-10T00:00:00Z"))).isFalse();
    }

    @Test
    void shouldRejectInvalidRecord() {
        assertInvalid("", "TPP-001", "hash-1", "FILE-001", BulkFileStatus.PROCESSING, Instant.parse("2026-02-10T00:00:00Z"), "idempotencyKey");
        assertInvalid("IDEMP-001", "", "hash-1", "FILE-001", BulkFileStatus.PROCESSING, Instant.parse("2026-02-10T00:00:00Z"), "tppId");
        assertInvalid("IDEMP-001", "TPP-001", "", "FILE-001", BulkFileStatus.PROCESSING, Instant.parse("2026-02-10T00:00:00Z"), "requestHash");
        assertInvalid("IDEMP-001", "TPP-001", "hash-1", "", BulkFileStatus.PROCESSING, Instant.parse("2026-02-10T00:00:00Z"), "fileId");
        assertInvalid("IDEMP-001", "TPP-001", "hash-1", "FILE-001", null, Instant.parse("2026-02-10T00:00:00Z"), "status");
        assertInvalid("IDEMP-001", "TPP-001", "hash-1", "FILE-001", BulkFileStatus.PROCESSING, null, "expiresAt");
    }

    private static void assertInvalid(String idempotencyKey,
                                      String tppId,
                                      String requestHash,
                                      String fileId,
                                      BulkFileStatus status,
                                      Instant expiresAt,
                                      String expectedField) {
        assertThatThrownBy(() -> new BulkIdempotencyRecord(
                idempotencyKey,
                tppId,
                requestHash,
                fileId,
                status,
                expiresAt
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedField);
    }
}
