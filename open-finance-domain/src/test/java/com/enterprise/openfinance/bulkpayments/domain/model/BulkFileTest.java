package com.enterprise.openfinance.bulkpayments.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BulkFileTest {

    @Test
    void shouldAdvanceProcessingToTerminalStatus() {
        BulkFile file = new BulkFile(
                "FILE-001",
                "CONS-BULK-001",
                "TPP-001",
                "IDEMP-001",
                "hash-1",
                "payroll.csv",
                BulkIntegrityMode.PARTIAL_REJECTION,
                BulkFileStatus.PROCESSING,
                BulkFileStatus.PARTIALLY_ACCEPTED,
                0,
                10,
                9,
                1,
                new BigDecimal("100.00"),
                Instant.parse("2026-02-09T10:00:00Z"),
                null
        );

        BulkFile firstPoll = file.advanceProcessing(2, Instant.parse("2026-02-09T10:00:01Z"));
        assertThat(firstPoll.status()).isEqualTo(BulkFileStatus.PROCESSING);
        assertThat(firstPoll.pollCount()).isEqualTo(1);
        assertThat(firstPoll.processedAt()).isNull();

        BulkFile secondPoll = firstPoll.advanceProcessing(2, Instant.parse("2026-02-09T10:00:02Z"));
        assertThat(secondPoll.status()).isEqualTo(BulkFileStatus.PARTIALLY_ACCEPTED);
        assertThat(secondPoll.isTerminal()).isTrue();
        assertThat(secondPoll.processedAt()).isEqualTo(Instant.parse("2026-02-09T10:00:02Z"));
        assertThat(secondPoll.belongsToTpp("TPP-001")).isTrue();
        assertThat(secondPoll.belongsToTpp("TPP-999")).isFalse();
    }

    @Test
    void shouldRejectInvalidFile() {
        assertInvalid("", "CONS", "TPP", "IDEMP", "hash", "file.csv", BulkIntegrityMode.PARTIAL_REJECTION, BulkFileStatus.PROCESSING, BulkFileStatus.COMPLETED, 0, 1, 1, 0, new BigDecimal("10.00"), Instant.parse("2026-02-09T10:00:00Z"), "fileId");
        assertInvalid("FILE", "", "TPP", "IDEMP", "hash", "file.csv", BulkIntegrityMode.PARTIAL_REJECTION, BulkFileStatus.PROCESSING, BulkFileStatus.COMPLETED, 0, 1, 1, 0, new BigDecimal("10.00"), Instant.parse("2026-02-09T10:00:00Z"), "consentId");
        assertInvalid("FILE", "CONS", "", "IDEMP", "hash", "file.csv", BulkIntegrityMode.PARTIAL_REJECTION, BulkFileStatus.PROCESSING, BulkFileStatus.COMPLETED, 0, 1, 1, 0, new BigDecimal("10.00"), Instant.parse("2026-02-09T10:00:00Z"), "tppId");
        assertInvalid("FILE", "CONS", "TPP", "", "hash", "file.csv", BulkIntegrityMode.PARTIAL_REJECTION, BulkFileStatus.PROCESSING, BulkFileStatus.COMPLETED, 0, 1, 1, 0, new BigDecimal("10.00"), Instant.parse("2026-02-09T10:00:00Z"), "idempotencyKey");
        assertInvalid("FILE", "CONS", "TPP", "IDEMP", "", "file.csv", BulkIntegrityMode.PARTIAL_REJECTION, BulkFileStatus.PROCESSING, BulkFileStatus.COMPLETED, 0, 1, 1, 0, new BigDecimal("10.00"), Instant.parse("2026-02-09T10:00:00Z"), "requestHash");
        assertInvalid("FILE", "CONS", "TPP", "IDEMP", "hash", "", BulkIntegrityMode.PARTIAL_REJECTION, BulkFileStatus.PROCESSING, BulkFileStatus.COMPLETED, 0, 1, 1, 0, new BigDecimal("10.00"), Instant.parse("2026-02-09T10:00:00Z"), "fileName");
        assertInvalid("FILE", "CONS", "TPP", "IDEMP", "hash", "file.csv", null, BulkFileStatus.PROCESSING, BulkFileStatus.COMPLETED, 0, 1, 1, 0, new BigDecimal("10.00"), Instant.parse("2026-02-09T10:00:00Z"), "integrityMode");
        assertInvalid("FILE", "CONS", "TPP", "IDEMP", "hash", "file.csv", BulkIntegrityMode.PARTIAL_REJECTION, null, BulkFileStatus.COMPLETED, 0, 1, 1, 0, new BigDecimal("10.00"), Instant.parse("2026-02-09T10:00:00Z"), "status");
        assertInvalid("FILE", "CONS", "TPP", "IDEMP", "hash", "file.csv", BulkIntegrityMode.PARTIAL_REJECTION, BulkFileStatus.PROCESSING, null, 0, 1, 1, 0, new BigDecimal("10.00"), Instant.parse("2026-02-09T10:00:00Z"), "targetStatus");
        assertInvalid("FILE", "CONS", "TPP", "IDEMP", "hash", "file.csv", BulkIntegrityMode.PARTIAL_REJECTION, BulkFileStatus.PROCESSING, BulkFileStatus.COMPLETED, -1, 1, 1, 0, new BigDecimal("10.00"), Instant.parse("2026-02-09T10:00:00Z"), "pollCount");
        assertInvalid("FILE", "CONS", "TPP", "IDEMP", "hash", "file.csv", BulkIntegrityMode.PARTIAL_REJECTION, BulkFileStatus.PROCESSING, BulkFileStatus.COMPLETED, 0, 0, 0, 0, new BigDecimal("10.00"), Instant.parse("2026-02-09T10:00:00Z"), "totalCount");
        assertInvalid("FILE", "CONS", "TPP", "IDEMP", "hash", "file.csv", BulkIntegrityMode.PARTIAL_REJECTION, BulkFileStatus.PROCESSING, BulkFileStatus.COMPLETED, 0, 1, 2, 0, new BigDecimal("10.00"), Instant.parse("2026-02-09T10:00:00Z"), "acceptedCount");
        assertInvalid("FILE", "CONS", "TPP", "IDEMP", "hash", "file.csv", BulkIntegrityMode.PARTIAL_REJECTION, BulkFileStatus.PROCESSING, BulkFileStatus.COMPLETED, 0, 1, 1, 1, new BigDecimal("10.00"), Instant.parse("2026-02-09T10:00:00Z"), "rejectedCount");
        assertInvalid("FILE", "CONS", "TPP", "IDEMP", "hash", "file.csv", BulkIntegrityMode.PARTIAL_REJECTION, BulkFileStatus.PROCESSING, BulkFileStatus.COMPLETED, 0, 1, 1, 0, null, Instant.parse("2026-02-09T10:00:00Z"), "totalAmount");
        assertInvalid("FILE", "CONS", "TPP", "IDEMP", "hash", "file.csv", BulkIntegrityMode.PARTIAL_REJECTION, BulkFileStatus.PROCESSING, BulkFileStatus.COMPLETED, 0, 1, 1, 0, new BigDecimal("0.00"), Instant.parse("2026-02-09T10:00:00Z"), "totalAmount");
        assertInvalid("FILE", "CONS", "TPP", "IDEMP", "hash", "file.csv", BulkIntegrityMode.PARTIAL_REJECTION, BulkFileStatus.PROCESSING, BulkFileStatus.COMPLETED, 0, 1, 1, 0, new BigDecimal("10.00"), null, "createdAt");
    }

    private static void assertInvalid(String fileId,
                                      String consentId,
                                      String tppId,
                                      String idempotencyKey,
                                      String requestHash,
                                      String fileName,
                                      BulkIntegrityMode integrityMode,
                                      BulkFileStatus status,
                                      BulkFileStatus targetStatus,
                                      int pollCount,
                                      int totalCount,
                                      int acceptedCount,
                                      int rejectedCount,
                                      BigDecimal totalAmount,
                                      Instant createdAt,
                                      String expectedField) {
        assertThatThrownBy(() -> new BulkFile(
                fileId,
                consentId,
                tppId,
                idempotencyKey,
                requestHash,
                fileName,
                integrityMode,
                status,
                targetStatus,
                pollCount,
                totalCount,
                acceptedCount,
                rejectedCount,
                totalAmount,
                createdAt,
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedField);
    }
}
