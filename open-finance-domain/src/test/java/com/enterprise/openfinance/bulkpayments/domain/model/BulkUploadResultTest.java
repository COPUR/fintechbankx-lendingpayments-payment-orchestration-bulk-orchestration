package com.enterprise.openfinance.bulkpayments.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BulkUploadResultTest {

    @Test
    void shouldCreateUploadResult() {
        BulkUploadResult result = new BulkUploadResult(
                "FILE-001",
                BulkFileStatus.PROCESSING,
                "ix-1",
                false,
                9,
                1,
                Instant.parse("2026-02-09T10:00:00Z")
        );

        assertThat(result.fileId()).isEqualTo("FILE-001");
        assertThat(result.status()).isEqualTo(BulkFileStatus.PROCESSING);
        assertThat(result.idempotencyReplay()).isFalse();
    }

    @Test
    void shouldRejectInvalidUploadResult() {
        assertInvalid("", BulkFileStatus.PROCESSING, "ix-1", false, 1, 0, Instant.parse("2026-02-09T10:00:00Z"), "fileId");
        assertInvalid("FILE-001", null, "ix-1", false, 1, 0, Instant.parse("2026-02-09T10:00:00Z"), "status");
        assertInvalid("FILE-001", BulkFileStatus.PROCESSING, "", false, 1, 0, Instant.parse("2026-02-09T10:00:00Z"), "interactionId");
        assertInvalid("FILE-001", BulkFileStatus.PROCESSING, "ix-1", false, -1, 0, Instant.parse("2026-02-09T10:00:00Z"), "acceptedCount");
        assertInvalid("FILE-001", BulkFileStatus.PROCESSING, "ix-1", false, 1, -1, Instant.parse("2026-02-09T10:00:00Z"), "rejectedCount");
        assertInvalid("FILE-001", BulkFileStatus.PROCESSING, "ix-1", false, 1, 0, null, "createdAt");
    }

    private static void assertInvalid(String fileId,
                                      BulkFileStatus status,
                                      String interactionId,
                                      boolean idempotencyReplay,
                                      int acceptedCount,
                                      int rejectedCount,
                                      Instant createdAt,
                                      String expectedField) {
        assertThatThrownBy(() -> new BulkUploadResult(
                fileId,
                status,
                interactionId,
                idempotencyReplay,
                acceptedCount,
                rejectedCount,
                createdAt
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedField);
    }
}
