package com.enterprise.openfinance.bulkpayments.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BulkIdempotencyRecordTest {

    @Test
    void aRecordIsPermanentAndHasNoExpiry() {
        BulkIdempotencyRecord record = new BulkIdempotencyRecord(
                " IDEMP-001 ", "TPP-001", "hash-1", "FILE-001", BulkFileStatus.PROCESSING);

        assertThat(record.idempotencyKey()).isEqualTo("IDEMP-001");
        assertThat(BulkIdempotencyRecord.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("expiresAt");
    }

    @Test
    void shouldRejectInvalidRecord() {
        assertInvalid("", "TPP-001", "hash-1", "FILE-001", BulkFileStatus.PROCESSING, "idempotencyKey");
        assertInvalid("IDEMP-001", "", "hash-1", "FILE-001", BulkFileStatus.PROCESSING, "tppId");
        assertInvalid("IDEMP-001", "TPP-001", "", "FILE-001", BulkFileStatus.PROCESSING, "requestHash");
        assertInvalid("IDEMP-001", "TPP-001", "hash-1", "", BulkFileStatus.PROCESSING, "fileId");
        assertInvalid("IDEMP-001", "TPP-001", "hash-1", "FILE-001", null, "status");
    }

    private static void assertInvalid(String idempotencyKey,
                                      String tppId,
                                      String requestHash,
                                      String fileId,
                                      BulkFileStatus status,
                                      String expectedField) {
        assertThatThrownBy(() -> new BulkIdempotencyRecord(idempotencyKey, tppId, requestHash, fileId, status))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedField);
    }
}
