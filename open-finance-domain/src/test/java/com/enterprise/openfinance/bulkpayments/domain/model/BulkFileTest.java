package com.enterprise.openfinance.bulkpayments.domain.model;

import com.enterprise.openfinance.bulkpayments.domain.event.BulkFileAccepted;
import com.enterprise.openfinance.bulkpayments.domain.event.BulkFileEvent;
import com.enterprise.openfinance.bulkpayments.domain.event.BulkFileRejected;
import com.enterprise.openfinance.bulkpayments.domain.exception.BusinessRuleViolationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BulkFileTest {

    private static final Instant UPLOADED = Instant.parse("2026-02-09T10:00:00Z");
    private static final Instant BATCH_1 = Instant.parse("2026-02-09T10:00:01Z");
    private static final Instant BATCH_2 = Instant.parse("2026-02-09T10:00:02Z");
    private static final Instant CONSENT_EXPIRES = Instant.parse("2026-02-10T10:00:00Z");

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
            assertThat(event.totalAmount()).isEqualTo(Money.of("350.00", "AED"));
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
                .hasMessageContaining("only 2 unprocessed items")
                .message().doesNotContain("FILE-001");
        assertThatThrownBy(() -> file.recordProcessedBatch(0, BATCH_1))
                .isInstanceOf(IllegalArgumentException.class);

        file.recordProcessedBatch(2, BATCH_1);
        assertThat(file.status()).isEqualTo(BulkFileStatus.VALIDATED);
        assertThatThrownBy(() -> file.recordProcessedBatch(1, BATCH_2))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("is not processing")
                .message().doesNotContain("FILE-001");
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

    @Test
    void amountsShareOneCurrency() {
        BulkFile file = accept(parsed(1, 1, 0, "10.00", "10.00", BulkFileStatus.VALIDATED));
        assertThat(file.currency()).isEqualTo(java.util.Currency.getInstance("AED"));

        assertThatThrownBy(() -> BulkFile.rehydrate("FILE", "CONS", "TPP", "IDEMP", "hash", "file.csv",
                BulkIntegrityMode.PARTIAL_REJECTION, BulkFileStatus.PROCESSING, BulkFileStatus.VALIDATED, 0, 1, 1, 0,
                Money.of("10.00", "AED"), Money.of("10", "JPY"), UPLOADED, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");
    }

    @Test
    void aFileWhoseConsentIsNoLongerUsableStopsAndIsPublishedAsRejected() {
        BulkFile file = accept(parsed(3, 2, 1, "350.00", "300.00", BulkFileStatus.VALIDATED));
        file.pullDomainEvents();
        file.recordProcessedBatch(1, BATCH_1);
        BulkFile stored = rehydrateAsStored(file, 1L);

        stored.stopBecauseConsentIsNotUsable(BATCH_2);

        assertThat(stored.status()).isEqualTo(BulkFileStatus.STOPPED);
        assertThat(stored.status().apiValue()).isEqualTo("Stopped");
        assertThat(stored.isTerminal()).isTrue();
        assertThat(stored.isValidationFinished()).isFalse();
        assertThat(stored.processedCount()).isEqualTo(1);
        assertThat(stored.processedAt()).isEqualTo(BATCH_2);
        assertThat(stored.pullDomainEvents()).singleElement().isInstanceOfSatisfying(BulkFileRejected.class, event -> {
            assertThat(event.reason()).isEqualTo(BulkFileRejected.Reason.CONSENT_NOT_USABLE);
            assertThat(event.totalCount()).isEqualTo(3);
            // None of the items will be released any more.
            assertThat(event.rejectedCount()).isEqualTo(3);
            assertThat(event.aggregateVersion()).isEqualTo(2L);
        });

        BulkFile reloaded = rehydrateAsStored(stored, 2L);
        assertThat(reloaded.status()).isEqualTo(BulkFileStatus.STOPPED);
        assertThatThrownBy(() -> reloaded.stopBecauseConsentIsNotUsable(BATCH_2))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> reloaded.recordProcessedBatch(1, BATCH_2))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    /**
     * What the file stands by is one rule for the status, the report and the Rejected event: a file of 700
     * items, 650 accepted and 50 rejected at validation, keeps 650 / 50 while processing and once Validated,
     * but once Stopped it releases nothing: 0 accepted, 700 rejected, the figures of its Rejected event.
     * The validation figures stay as stored.
     */
    @Test
    void aStoppedFileStandsByNoAcceptedItemWhileItsValidationFiguresStayAsStored() {
        BulkFile file = accept(parsed(700, 650, 50, "7000", "6500", BulkFileStatus.VALIDATED));
        file.pullDomainEvents();
        assertThat(file.releasedAcceptedCount()).isEqualTo(650);
        assertThat(file.releasedRejectedCount()).isEqualTo(50);

        file.recordProcessedBatch(500, BATCH_1);
        BulkFile stored = rehydrateAsStored(file, 1L);
        assertThat(stored.releasedAcceptedCount()).isEqualTo(650);
        assertThat(stored.releasedRejectedCount()).isEqualTo(50);

        stored.stopBecauseConsentIsNotUsable(BATCH_2);

        assertThat(stored.releasedAcceptedCount()).isZero();
        assertThat(stored.releasedRejectedCount()).isEqualTo(700);
        assertThat(stored.pullDomainEvents()).singleElement().isInstanceOfSatisfying(BulkFileRejected.class,
                event -> assertThat(event.rejectedCount()).isEqualTo(stored.releasedRejectedCount()));
        assertThat(stored.acceptedCount()).as("validation figure, as stored").isEqualTo(650);
        assertThat(stored.rejectedCount()).as("validation figure, as stored").isEqualTo(50);
        BulkFile reloaded = rehydrateAsStored(stored, 2L);
        assertThat(reloaded.releasedAcceptedCount()).isZero();
        assertThat(reloaded.releasedRejectedCount()).isEqualTo(700);
    }

    @Test
    void aValidatedFileStandsByItsValidationFigures() {
        BulkFile file = accept(parsed(3, 2, 1, "30", "20", BulkFileStatus.VALIDATED));
        file.recordProcessedBatch(3, BATCH_1);

        assertThat(file.status()).isEqualTo(BulkFileStatus.VALIDATED);
        assertThat(file.releasedAcceptedCount()).isEqualTo(2);
        assertThat(file.releasedRejectedCount()).isEqualTo(1);
    }

    /** The consent service's rule: active while its expiry is after now; so expired from that instant on. */
    @Test
    void theConsentExpiryReadAtUploadIsKeptAndPassesAtThatInstant() {
        BulkFile file = accept(parsed(1, 1, 0, "10", "10", BulkFileStatus.VALIDATED));

        assertThat(file.consentExpiresAt()).isEqualTo(CONSENT_EXPIRES);
        assertThat(file.consentExpiredAt(CONSENT_EXPIRES.minusNanos(1000))).isFalse();
        assertThat(file.consentExpiredAt(CONSENT_EXPIRES)).isTrue();
        assertThat(rehydrateAsStored(file, 0L).consentExpiredAt(CONSENT_EXPIRES)).isTrue();

        BulkFile storedBeforeV14 = BulkFile.rehydrate("FILE", "CONS", "TPP", "IDEMP", "hash", "file.csv",
                BulkIntegrityMode.PARTIAL_REJECTION, BulkFileStatus.PROCESSING, BulkFileStatus.VALIDATED, 0, 1, 1, 0,
                Money.of("10", "AED"), Money.of("10", "AED"), UPLOADED, null, null, 0);
        assertThat(storedBeforeV14.consentExpiresAt()).isNull();
        assertThat(storedBeforeV14.consentExpiredAt(Instant.parse("2100-01-01T00:00:00Z"))).as("unknown").isFalse();
        assertThatThrownBy(() -> BulkFile.accept("FILE", "CONS", "TPP", "IDEMP", "hash", "file.csv",
                BulkIntegrityMode.PARTIAL_REJECTION, parsed(1, 1, 0, "10", "10", BulkFileStatus.VALIDATED), null, UPLOADED))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void anAllRejectedFileSaysWhyItWasRejected() {
        BulkFile file = accept(parsed(1, 0, 1, "10.00", "0", BulkFileStatus.REJECTED));
        file.pullDomainEvents();
        file.recordProcessedBatch(1, BATCH_1);

        assertThat(file.pullDomainEvents()).singleElement().isInstanceOfSatisfying(BulkFileRejected.class,
                event -> assertThat(event.reason()).isEqualTo(BulkFileRejected.Reason.ALL_ITEMS_REJECTED));
    }

    private static BulkFile accept(ParsedBulkFile parsed) {
        return BulkFile.accept("FILE-001", "CONS-BULK-001", "TPP-001", "IDEMP-001", "hash-1", "payroll.csv",
                BulkIntegrityMode.PARTIAL_REJECTION, parsed, CONSENT_EXPIRES, UPLOADED);
    }

    private static BulkFile rehydrateAsStored(BulkFile file, long version) {
        return BulkFile.rehydrate(file.fileId(), file.consentId(), file.tppId(), file.idempotencyKey(),
                file.requestHash(), file.fileName(), file.integrityMode(), file.status(), file.targetStatus(),
                file.processedCount(), file.totalCount(), file.acceptedCount(), file.rejectedCount(),
                file.totalAmount(), file.acceptedAmount(), file.createdAt(), file.processedAt(),
                file.consentExpiresAt(), version);
    }

    private static ParsedBulkFile parsed(int total, int accepted, int rejected, String totalAmount,
                                         String acceptedAmount, BulkFileStatus target) {
        List<BulkItemResult> items = new java.util.ArrayList<>();
        for (int line = 1; line <= total; line++) {
            items.add(line <= accepted
                    ? BulkItemResult.accepted(line, "INS-" + line, "AE120001000000000000000001", Money.of("10", "AED"))
                    : BulkItemResult.rejected(line, "INS-" + line, "AE000", Money.of("10", "AED"), "Invalid IBAN"));
        }
        return new ParsedBulkFile(items, total, accepted, rejected, Money.of(totalAmount, "AED"),
                Money.of(acceptedAmount, "AED"), target);
    }

    private static void assertInvalid(String fileId, BulkFileStatus status, BulkFileStatus targetStatus,
                                      int processedCount, int totalCount, int acceptedCount, int rejectedCount,
                                      String totalAmount, String acceptedAmount, Instant createdAt,
                                      Instant processedAt, long version, String expectedMessage) {
        assertThatThrownBy(() -> BulkFile.rehydrate(fileId, "CONS", "TPP", "IDEMP", "hash", "file.csv",
                BulkIntegrityMode.PARTIAL_REJECTION, status, targetStatus, processedCount, totalCount, acceptedCount,
                rejectedCount, Money.of(totalAmount, "AED"), Money.of(acceptedAmount, "AED"), createdAt, processedAt,
                null, version))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedMessage);
    }
}
