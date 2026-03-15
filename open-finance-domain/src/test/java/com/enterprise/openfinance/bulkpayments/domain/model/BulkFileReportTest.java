package com.enterprise.openfinance.bulkpayments.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BulkFileReportTest {

    @Test
    void shouldCreateReportAndExposeComputedFields() {
        BulkFileReport report = new BulkFileReport(
                "FILE-001",
                BulkFileStatus.PARTIALLY_ACCEPTED,
                2,
                1,
                1,
                List.of(
                        BulkItemResult.accepted(1, "INS-1", "AE120001000000000000000001", new BigDecimal("10.00")),
                        BulkItemResult.rejected(2, "INS-2", "AE999", new BigDecimal("5.00"), "Invalid IBAN")
                ),
                Instant.parse("2026-02-09T10:00:00Z")
        );

        assertThat(report.fileId()).isEqualTo("FILE-001");
        assertThat(report.status()).isEqualTo(BulkFileStatus.PARTIALLY_ACCEPTED);
        assertThat(report.items()).hasSize(2);
    }

    @Test
    void shouldRejectInvalidReport() {
        assertInvalid("", BulkFileStatus.COMPLETED, 1, 1, 0, List.of(BulkItemResult.accepted(1, "INS-1", "AE120001000000000000000001", new BigDecimal("10.00"))), Instant.parse("2026-02-09T10:00:00Z"), "fileId");
        assertInvalid("FILE-001", null, 1, 1, 0, List.of(BulkItemResult.accepted(1, "INS-1", "AE120001000000000000000001", new BigDecimal("10.00"))), Instant.parse("2026-02-09T10:00:00Z"), "status");
        assertInvalid("FILE-001", BulkFileStatus.COMPLETED, 0, 0, 0, List.of(BulkItemResult.accepted(1, "INS-1", "AE120001000000000000000001", new BigDecimal("10.00"))), Instant.parse("2026-02-09T10:00:00Z"), "totalCount");
        assertInvalid("FILE-001", BulkFileStatus.COMPLETED, 1, 2, 0, List.of(BulkItemResult.accepted(1, "INS-1", "AE120001000000000000000001", new BigDecimal("10.00"))), Instant.parse("2026-02-09T10:00:00Z"), "acceptedCount");
        assertInvalid("FILE-001", BulkFileStatus.COMPLETED, 1, 1, 1, List.of(BulkItemResult.accepted(1, "INS-1", "AE120001000000000000000001", new BigDecimal("10.00"))), Instant.parse("2026-02-09T10:00:00Z"), "rejectedCount");
        assertInvalid("FILE-001", BulkFileStatus.COMPLETED, 1, 1, 0, null, Instant.parse("2026-02-09T10:00:00Z"), "items");
        assertInvalid("FILE-001", BulkFileStatus.COMPLETED, 1, 1, 0, List.of(BulkItemResult.accepted(1, "INS-1", "AE120001000000000000000001", new BigDecimal("10.00"))), null, "generatedAt");
    }

    private static void assertInvalid(String fileId,
                                      BulkFileStatus status,
                                      int totalCount,
                                      int acceptedCount,
                                      int rejectedCount,
                                      List<BulkItemResult> items,
                                      Instant generatedAt,
                                      String expectedField) {
        assertThatThrownBy(() -> new BulkFileReport(
                fileId,
                status,
                totalCount,
                acceptedCount,
                rejectedCount,
                items,
                generatedAt
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedField);
    }
}
