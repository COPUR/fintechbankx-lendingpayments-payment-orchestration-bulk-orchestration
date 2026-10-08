package com.enterprise.openfinance.bulkpayments.domain.model;

import com.enterprise.openfinance.bulkpayments.domain.event.BulkFileAccepted;
import com.enterprise.openfinance.bulkpayments.domain.event.BulkFileEvent;
import com.enterprise.openfinance.bulkpayments.domain.event.BulkFileRejected;
import com.enterprise.openfinance.bulkpayments.domain.exception.BusinessRuleViolationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BulkFileTest {

    private static final Instant UPLOADED = Instant.parse("2026-02-09T10:00:00Z");
    private static final Instant BATCH_1 = Instant.parse("2026-02-09T10:00:01Z");
    private static final Instant BATCH_2 = Instant.parse("2026-02-09T10:00:02Z");

    @Test
    void acceptRaisesAcceptedEventWithTheParsedFigures() {
        BulkFile file = accept(parsed(3, 2, 1, "350.00", "300.00", BulkFileStatus.VALIDATED));

        assertThat(file.status()).isEqualTo(BulkFileStatus.PROCESSING);
        assertThat(file.processedCount()).isZero();
        assertThat(file.remainingItems()).isEqualTo(3);
        assertThat(file.version()).isZero();
        assertThat(file.processedAt()).isNull();

        List<BulkFileEvent> events = file.pullDomainEvents();
        assertThat(events).singleElement().isInstanceOfSatisfying(BulkFileAccepted.class, event -> {
            assertThat(event.fileId()).isEqualTo("FILE-001");
            assertThat(event.aggregateVersion()).isZero();
            assertThat(event.occurredAt()).isEqualTo(UPLOADED);
            assertThat(event.totalCount()).isEqualTo(3);
            assertThat(event.acceptedCount()).isEqualTo(2);
            assertThat(event.rejectedCount()).isEqualTo(1);
            assertThat(event.totalAmount()).isEqualByComparingTo("350.00");
            assertThat(event.integrityMode()).isEqualTo(BulkIntegrityMode.PARTIAL_REJECTION);
        });
        assertThat(file.pullDomainEvents()).isEmpty();
    }

    @Test
    void staysProcessingUntilTheLastBatchThenIsValidatedWithoutClaimingCompletion() {
        BulkFile file = accept(parsed(3, 2, 1, "350.00", "300.00", BulkFileStatus.VALIDATED));
        file.pullDomainEvents();

        file.recordProcessedBatch(2, BATCH_1);
        assertThat(file.status()).isEqualTo(BulkFileStatus.PROCESSING);
        assertThat(file.processedCount()).isEqualTo(2);
        assertThat(file.remainingItems()).isEqualTo(1);
        assertThat(file.pullDomainEvents()).isEmpty();

        BulkFile stored = rehydrateAsStored(file, 1L);
        stored.recordProcessedBatch(1, BATCH_2);

        assertThat(stored.status()).isEqualTo(BulkFileStatus.VALIDATED);
        assertThat(stored.isValidationFinished()).isTrue();
        assertThat(stored.isTerminal()).isFalse();
        assertThat(stored.processedAt()).isEqualTo(BATCH_2);
        // No item has reached initiation-settlement, so nothing is published as completed.
        assertThat(stored.pullDomainEvents()).isEmpty();
    }

    @Test
    void fileWithNoAcceptedItemRaisesRejectedEvent() {
        BulkFile file = accept(parsed(2, 0, 2, "30.00", "0", BulkFileStatus.REJECTED));
        file.pullDomainEvents();

        file.recordProcessedBatch(2, BATCH_1);

        assertThat(file.status()).isEqualTo(BulkFileStatus.REJECTED);
        assertThat(file.pullDomainEvents()).singleElement().isInstanceOfSatisfying(BulkFileRejected.class, event -> {
            assertThat(event.totalCount()).isEqualTo(2);
            assertThat(event.rejectedCount()).isEqualTo(2);
            assertThat(event.aggregateVersion()).isEqualTo(1L);
        });
    }

    @Test
    void rejectsProcessingMoreItemsThanRemainOrAfterTheFileIsDone() {
        BulkFile file = accept(parsed(2, 2, 0, "20.00", "20.00", BulkFileStatus.VALIDATED));

        assertThatThrownBy(() -> file.recordProcessedBatch(3, BATCH_1))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("only 2 unprocessed items");
        assertThatThrownBy(() -> file.recordProcessedBatch(0, BATCH_1))
                .isInstanceOf(IllegalArgumentException.class);

        file.recordProcessedBatch(2, BATCH_1);
        assertThat(file.status()).isEqualTo(BulkFileStatus.VALIDATED);
        assertThatThrownBy(() -> file.recordProcessedBatch(1, BATCH_2))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("is not processing");
    }

    @Test
    void ownershipIsCheckedAgainstTheUploadingTpp() {
        BulkFile file = accept(parsed(1, 1, 0, "10.00", "10.00", BulkFileStatus.VALIDATED));

        assertThat(file.belongsToTpp("TPP-001")).isTrue();
        assertThat(file.belongsToTpp("TPP-999")).isFalse();
    }

    @Test
    void rehydrateGuardsEveryInvariant() {
        assertInvalid("", BulkFileStatus.PROCESSING, BulkFileStatus.VALIDATED, 0, 1, 1, 0, "10.00", "10.00", UPLOADED, null, 0, "fileId");
        assertInvalid("FILE", null, BulkFileStatus.VALIDATED, 0, 1, 1, 0, "10.00", "10.00", UPLOADED, null, 0, "status");
        assertInvalid("FILE", BulkFileStatus.PROCESSING, BulkFileStatus.PROCESSING, 0, 1, 1, 0, "10.00", "10.00", UPLOADED, null, 0, "targetStatus");
        assertInvalid("FILE", BulkFileStatus.REJECTED, BulkFileStatus.VALIDATED, 1, 1, 1, 0, "10.00", "10.00", UPLOADED, BATCH_1, 0, "status must be PROCESSING");
        assertInvalid("FILE", BulkFileStatus.PROCESSING, BulkFileStatus.VALIDATED, 0, 0, 0, 0, "10.00", "10.00", UPLOADED, null, 0, "totalCount");
        assertInvalid("FILE", BulkFileStatus.PROCESSING, BulkFileStatus.VALIDATED, 2, 1, 1, 0, "10.00", "10.00", UPLOADED, null, 0, "processedCount out of range");
        assertInvalid("FILE", BulkFileStatus.PROCESSING, BulkFileStatus.VALIDATED, 1, 1, 1, 0, "10.00", "10.00", UPLOADED, null, 0, "processedCount must equal totalCount");
        assertInvalid("FILE", BulkFileStatus.PROCESSING, BulkFileStatus.VALIDATED, 0, 1, 2, 0, "10.00", "10.00", UPLOADED, null, 0, "acceptedCount");
        assertInvalid("FILE", BulkFileStatus.PROCESSING, BulkFileStatus.VALIDATED, 0, 1, 1, -1, "10.00", "10.00", UPLOADED, null, 0, "rejectedCount");
        assertInvalid("FILE", BulkFileStatus.PROCESSING, BulkFileStatus.VALIDATED, 0, 2, 1, 0, "10.00", "10.00", UPLOADED, null, 0, "must equal totalCount");
        assertInvalid("FILE", BulkFileStatus.PROCESSING, BulkFileStatus.VALIDATED, 0, 1, 1, 0, "0.00", "0.00", UPLOADED, null, 0, "totalAmount");
        assertInvalid("FILE", BulkFileStatus.PROCESSING, BulkFileStatus.VALIDATED, 0, 1, 1, 0, "10.00", "10.01", UPLOADED, null, 0, "acceptedAmount");
        assertInvalid("FILE", BulkFileStatus.PROCESSING, BulkFileStatus.VALIDATED, 0, 1, 1, 0, "10.00", "10.00", null, null, 0, "createdAt");
        assertInvalid("FILE", BulkFileStatus.VALIDATED, BulkFileStatus.VALIDATED, 1, 1, 1, 0, "10.00", "10.00", UPLOADED, null, 0, "processedAt");
        assertInvalid("FILE", BulkFileStatus.PROCESSING, BulkFileStatus.VALIDATED, 0, 1, 1, 0, "10.00", "10.00", UPLOADED, null, -1, "version");
    }

    private static BulkFile accept(ParsedBulkFile parsed) {
        return BulkFile.accept("FILE-001", "CONS-BULK-001", "TPP-001", "IDEMP-001", "hash-1", "payroll.csv",
                BulkIntegrityMode.PARTIAL_REJECTION, parsed, UPLOADED);
    }

    private static BulkFile rehydrateAsStored(BulkFile file, long version) {
        return BulkFile.rehydrate(file.fileId(), file.consentId(), file.tppId(), file.idempotencyKey(),
                file.requestHash(), file.fileName(), file.integrityMode(), file.status(), file.targetStatus(),
                file.processedCount(), file.totalCount(), file.acceptedCount(), file.rejectedCount(),
                file.totalAmount(), file.acceptedAmount(), file.createdAt(), file.processedAt(), version);
    }

    private static ParsedBulkFile parsed(int total, int accepted, int rejected, String totalAmount,
                                         String acceptedAmount, BulkFileStatus target) {
        List<BulkItemResult> items = new java.util.ArrayList<>();
        for (int line = 1; line <= total; line++) {
            items.add(line <= accepted
                    ? BulkItemResult.accepted(line, "INS-" + line, "AE120001000000000000000001", BigDecimal.TEN)
                    : BulkItemResult.rejected(line, "INS-" + line, "AE000", BigDecimal.TEN, "Invalid IBAN"));
        }
        return new ParsedBulkFile(items, total, accepted, rejected, new BigDecimal(totalAmount),
                new BigDecimal(acceptedAmount), target);
    }

    private static void assertInvalid(String fileId, BulkFileStatus status, BulkFileStatus targetStatus,
                                      int processedCount, int totalCount, int acceptedCount, int rejectedCount,
                                      String totalAmount, String acceptedAmount, Instant createdAt,
                                      Instant processedAt, long version, String expectedMessage) {
        assertThatThrownBy(() -> BulkFile.rehydrate(fileId, "CONS", "TPP", "IDEMP", "hash", "file.csv",
                BulkIntegrityMode.PARTIAL_REJECTION, status, targetStatus, processedCount, totalCount, acceptedCount,
                rejectedCount, new BigDecimal(totalAmount), new BigDecimal(acceptedAmount), createdAt, processedAt,
                version))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedMessage);
    }
}
