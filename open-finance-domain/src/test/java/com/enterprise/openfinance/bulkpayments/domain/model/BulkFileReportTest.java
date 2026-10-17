package com.enterprise.openfinance.bulkpayments.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BulkFileReportTest {

    @Test
    void shouldCreateReportAndExposeComputedFields() {
        BulkFileReport report = new BulkFileReport(
                "FILE-001",
                BulkFileStatus.VALIDATED,
                2,
                1,
                1,
                List.of(
                        BulkItemResult.accepted(1, "INS-1", "AE120001000000000000000001", Money.of("10.00", "AED")),
                        BulkItemResult.rejected(2, "INS-2", "AE999", Money.of("5.00", "AED"), "Invalid IBAN")
                ),
                Instant.parse("2026-02-09T10:00:00Z")
        );

        assertThat(report.fileId()).isEqualTo("FILE-001");
        assertThat(report.status()).isEqualTo(BulkFileStatus.VALIDATED);
        assertThat(report.items()).hasSize(2);
    }

    /**
     * A STOPPED file releases nothing (its Rejected event, reason CONSENT_NOT_USABLE, counts every item as
     * rejected), so its report says the same: every item Rejected, accepted ones with "Consent not usable",
     * AcceptedCount 0. Items rejected at validation keep their own reason.
     */
    @Test
    void aStoppedFilesReportShowsNoItemReleased() {
        Instant uploaded = Instant.parse("2026-02-09T10:00:00Z");
        BulkFile stopped = BulkFile.rehydrate("FILE-001", "CONS-1", "TPP-001", "IDEMP-1", "hash", "payroll.csv",
                BulkIntegrityMode.PARTIAL_REJECTION, BulkFileStatus.STOPPED, BulkFileStatus.VALIDATED, 1, 3, 2, 1,
                Money.of("35.00", "AED"), Money.of("30.00", "AED"), uploaded, uploaded.plusSeconds(2), null, 2L);
        List<BulkItemResult> stored = List.of(
                BulkItemResult.accepted(1, "INS-1", "AE120001000000000000000001", Money.of("10.00", "AED")),
                BulkItemResult.accepted(2, "INS-2", "AE120001000000000000000001", Money.of("20.00", "AED")),
                BulkItemResult.rejected(3, "INS-3", "AE999", Money.of("5.00", "AED"), "Invalid IBAN"));

        BulkFileReport report = BulkFileReport.of(stopped, stored, uploaded.plusSeconds(3));

        assertThat(report.status()).isEqualTo(BulkFileStatus.STOPPED);
        assertThat(report.totalCount()).isEqualTo(3);
        assertThat(report.acceptedCount()).isZero();
        assertThat(report.rejectedCount()).isEqualTo(3);
        assertThat(report.items()).extracting(BulkItemResult::status).containsOnly(BulkItemStatus.REJECTED);
        assertThat(report.items()).extracting(BulkItemResult::errorMessage)
                .containsExactly(BulkFileReport.CONSENT_NOT_USABLE, BulkFileReport.CONSENT_NOT_USABLE, "Invalid IBAN");
        assertThat(report.items()).extracting(BulkItemResult::amount)
                .containsExactly(Money.of("10.00", "AED"), Money.of("20.00", "AED"), Money.of("5.00", "AED"));
    }

    @Test
    void aValidatedFilesReportShowsItsItemsAsStored() {
        Instant uploaded = Instant.parse("2026-02-09T10:00:00Z");
        BulkFile validated = BulkFile.rehydrate("FILE-001", "CONS-1", "TPP-001", "IDEMP-1", "hash", "payroll.csv",
                BulkIntegrityMode.PARTIAL_REJECTION, BulkFileStatus.VALIDATED, BulkFileStatus.VALIDATED, 2, 2, 1, 1,
                Money.of("15.00", "AED"), Money.of("10.00", "AED"), uploaded, uploaded.plusSeconds(2), null, 2L);
        List<BulkItemResult> stored = List.of(
                BulkItemResult.accepted(1, "INS-1", "AE120001000000000000000001", Money.of("10.00", "AED")),
                BulkItemResult.rejected(2, "INS-2", "AE999", Money.of("5.00", "AED"), "Invalid IBAN"));

        BulkFileReport report = BulkFileReport.of(validated, stored, uploaded.plusSeconds(3));

        assertThat(report.acceptedCount()).isEqualTo(1);
        assertThat(report.rejectedCount()).isEqualTo(1);
        assertThat(report.items()).isEqualTo(stored);
    }

    @Test
    void shouldRejectInvalidReport() {
        assertInvalid("", BulkFileStatus.VALIDATED, 1, 1, 0, List.of(BulkItemResult.accepted(1, "INS-1", "AE120001000000000000000001", Money.of("10.00", "AED"))), Instant.parse("2026-02-09T10:00:00Z"), "fileId");
        assertInvalid("FILE-001", null, 1, 1, 0, List.of(BulkItemResult.accepted(1, "INS-1", "AE120001000000000000000001", Money.of("10.00", "AED"))), Instant.parse("2026-02-09T10:00:00Z"), "status");
        assertInvalid("FILE-001", BulkFileStatus.VALIDATED, 0, 0, 0, List.of(BulkItemResult.accepted(1, "INS-1", "AE120001000000000000000001", Money.of("10.00", "AED"))), Instant.parse("2026-02-09T10:00:00Z"), "totalCount");
        assertInvalid("FILE-001", BulkFileStatus.VALIDATED, 1, 2, 0, List.of(BulkItemResult.accepted(1, "INS-1", "AE120001000000000000000001", Money.of("10.00", "AED"))), Instant.parse("2026-02-09T10:00:00Z"), "acceptedCount");
        assertInvalid("FILE-001", BulkFileStatus.VALIDATED, 1, 1, 1, List.of(BulkItemResult.accepted(1, "INS-1", "AE120001000000000000000001", Money.of("10.00", "AED"))), Instant.parse("2026-02-09T10:00:00Z"), "rejectedCount");
        assertInvalid("FILE-001", BulkFileStatus.VALIDATED, 1, 1, 0, null, Instant.parse("2026-02-09T10:00:00Z"), "items");
        assertInvalid("FILE-001", BulkFileStatus.VALIDATED, 1, 1, 0, List.of(BulkItemResult.accepted(1, "INS-1", "AE120001000000000000000001", Money.of("10.00", "AED"))), null, "generatedAt");
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
