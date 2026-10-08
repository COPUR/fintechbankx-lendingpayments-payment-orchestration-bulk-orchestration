package com.enterprise.openfinance.bulkpayments.application;

import com.enterprise.openfinance.bulkpayments.domain.port.in.command.SubmitBulkFileCommand;
import com.enterprise.openfinance.bulkpayments.domain.event.BulkFileAccepted;
import com.enterprise.openfinance.bulkpayments.domain.event.BulkFileEvent;
import com.enterprise.openfinance.bulkpayments.domain.event.BulkFileRejected;
import com.enterprise.openfinance.bulkpayments.domain.exception.BusinessRuleViolationException;
import com.enterprise.openfinance.bulkpayments.domain.exception.ConsentAlreadyUsedException;
import com.enterprise.openfinance.bulkpayments.domain.exception.ForbiddenException;
import com.enterprise.openfinance.bulkpayments.domain.exception.IdempotencyConflictException;
import com.enterprise.openfinance.bulkpayments.domain.exception.ResourceNotFoundException;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkConsentBinding;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkConsentContext;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkFile;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkFileReport;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkFileStatus;
import com.enterprise.openfinance.bulkpayments.domain.model.Money;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkIdempotencyRecord;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkIntegrityMode;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkItemResult;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkItemStatus;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkSettings;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkUploadResult;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkCachePort;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkConsentBindingPort;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkConsentPort;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkFileEventPort;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkFilePort;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkIdempotencyPort;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkItemPort;
import com.enterprise.openfinance.bulkpayments.domain.port.in.query.GetBulkFileReportQuery;
import com.enterprise.openfinance.bulkpayments.domain.port.in.query.GetBulkFileStatusQuery;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class BulkPaymentServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-02-09T10:00:00Z"), ZoneOffset.UTC);
    private static final String IBAN = "AE120001000000000000000001";

    private final TestConsentPort consentPort = new TestConsentPort();
    private final TestFilePort filePort = new TestFilePort();
    private final TestItemPort itemPort = new TestItemPort();
    private final TestIdempotencyPort idempotencyPort = new TestIdempotencyPort();
    private final TestCachePort cachePort = new TestCachePort();
    private final TestBindingPort bindingPort = new TestBindingPort();
    private final RecordingPublisher publisher = new RecordingPublisher();

    @Test
    void shouldAcceptValidUploadAndCompleteOnlyWhenTheProcessorHasRun() {
        BulkPaymentService service = service(settings(2));
        BulkFileProcessingService processor = processor(settings(2));

        String content = validCsv("INS-1," + IBAN + ",10.00");
        BulkUploadResult upload = service.submitFile(command("IDEMP-001", content, BulkIntegrityMode.PARTIAL_REJECTION));

        assertThat(upload.status()).isEqualTo(BulkFileStatus.PROCESSING);
        assertThat(upload.idempotencyReplay()).isFalse();
        assertThat(upload.acceptedCount()).isEqualTo(1);
        assertThat(itemPort.items.get(upload.fileId())).hasSize(1);
        assertThat(publisher.published).singleElement().isInstanceOf(BulkFileAccepted.class);

        // Reading the status no longer moves the file; only processing does.
        status(service, upload.fileId());
        assertThat(status(service, upload.fileId()).status()).isEqualTo(BulkFileStatus.PROCESSING);

        assertThat(processor.processNextBatch()).isEqualTo(1);
        assertThat(processor.processNextBatch()).isZero();

        BulkFile done = status(service, upload.fileId());
        assertThat(done.status()).isEqualTo(BulkFileStatus.VALIDATED);
        assertThat(done.processedAt()).isEqualTo(Instant.now(CLOCK));

        BulkFileReport report = report(service, upload.fileId());
        assertThat(report.status()).isEqualTo(BulkFileStatus.VALIDATED);
        assertThat(report.acceptedCount()).isEqualTo(1);
        assertThat(report.rejectedCount()).isZero();
        // No hand-off to initiation-settlement exists yet, so no completion is published.
        assertThat(publisher.published).singleElement().isInstanceOf(BulkFileAccepted.class);
    }

    @Test
    void aConsentAuthorisesOneFileAndRecordsWhatItAuthorised() {
        BulkPaymentService service = service(settings(10));
        consentPort.data.put("CONS-SHARED", new BulkConsentContext("CONS-SHARED", "TPP-001", Set.of("INITIATEBULKPAYMENTS"),
                Instant.parse("2099-01-01T00:00:00Z"), true));
        String first = validCsv("INS-1," + IBAN + ",10.00", "INS-2,AE000,5.50");
        String second = validCsv("INS-9," + IBAN + ",99.00");

        BulkUploadResult upload = service.submitFile(
                command("CONS-SHARED", "IDEMP-B1", first, BulkIntegrityMode.PARTIAL_REJECTION, "AED"));

        assertThat(bindingPort.findByConsentId("CONS-SHARED")).hasValueSatisfying(binding -> {
            assertThat(binding.fileId()).isEqualTo(upload.fileId());
            assertThat(binding.tppId()).isEqualTo("TPP-001");
            assertThat(binding.fileHash()).isEqualTo(sha256(first));
            assertThat(binding.itemCount()).isEqualTo(2);
            assertThat(binding.controlSum()).isEqualTo(Money.of("15.50", "AED"));
            assertThat(binding.boundAt()).isEqualTo(Instant.now(CLOCK));
        });

        assertThatThrownBy(() -> service.submitFile(
                command("CONS-SHARED", "IDEMP-B2", second, BulkIntegrityMode.PARTIAL_REJECTION, "AED")))
                .isInstanceOf(ConsentAlreadyUsedException.class)
                .hasMessage("Consent already used for another file");
        assertThat(filePort.data).hasSize(1);
        assertThat(publisher.published).hasSize(1);

        // A replay of the first upload is still answered from idempotency, not refused.
        BulkUploadResult replay = service.submitFile(
                command("CONS-SHARED", "IDEMP-B1", first, BulkIntegrityMode.PARTIAL_REJECTION, "AED"));
        assertThat(replay.idempotencyReplay()).isTrue();
        assertThat(replay.fileId()).isEqualTo(upload.fileId());
    }

    @Test
    void theConsentIsReadAgainBeforeEachBatchAndAFileStopsWhenItIsNoLongerUsable() {
        BulkPaymentService service = service(settings(2));
        BulkFileProcessingService processor = processor(settings(2));
        BulkUploadResult upload = service.submitFile(command("IDEMP-REVOKE", validCsv(
                "INS-1," + IBAN + ",10.00", "INS-2," + IBAN + ",20.00", "INS-3," + IBAN + ",30.00"),
                BulkIntegrityMode.PARTIAL_REJECTION));
        String consentId = "CONS-IDEMP-REVOKE";

        assertThat(processor.processNextBatch()).isEqualTo(2);
        assertThat(consentPort.reads.get(consentId)).isEqualTo(2); // upload + first batch

        // The PSU revokes the consent between batches: usable is now false.
        consentPort.data.put(consentId, new BulkConsentContext(consentId, "TPP-001",
                Set.of("INITIATEBULKPAYMENTS"), Instant.parse("2099-01-01T00:00:00Z"), false));
        assertThat(processor.processNextBatch()).isZero();

        BulkFile stopped = status(service, upload.fileId());
        assertThat(stopped.status()).isEqualTo(BulkFileStatus.STOPPED);
        assertThat(stopped.processedCount()).isEqualTo(2);
        assertThat(itemPort.processed.get(upload.fileId())).containsExactlyInAnyOrder(1, 2);
        assertThat(publisher.published).last().isInstanceOfSatisfying(BulkFileRejected.class,
                event -> assertThat(event.reason()).isEqualTo(BulkFileRejected.Reason.CONSENT_NOT_USABLE));
        assertThat(processor.processNextBatch()).isZero();
        assertThat(consentPort.reads.get(consentId)).isEqualTo(3);
    }

    @Test
    void aConsentThatDisappearedOrExpiredAlsoStopsTheFileButAnOutageDoesNot() {
        BulkPaymentService service = service(settings(1));
        BulkFileProcessingService processor = processor(settings(1));
        String content = validCsv("INS-1," + IBAN + ",10.00", "INS-2," + IBAN + ",20.00");
        BulkUploadResult gone = service.submitFile(command("IDEMP-GONE", content, BulkIntegrityMode.PARTIAL_REJECTION));

        consentPort.failNextRead = true;
        assertThatThrownBy(processor::processNextBatch).isInstanceOf(IllegalStateException.class);
        assertThat(status(service, gone.fileId()).status()).isEqualTo(BulkFileStatus.PROCESSING);

        consentPort.data.remove("CONS-IDEMP-GONE");
        consentPort.missing.add("CONS-IDEMP-GONE");
        assertThat(processor.processNextBatch()).isZero();
        assertThat(status(service, gone.fileId()).status()).isEqualTo(BulkFileStatus.STOPPED);

        BulkUploadResult expiring = service.submitFile(command("IDEMP-EXP", content, BulkIntegrityMode.PARTIAL_REJECTION));
        consentPort.data.put("CONS-IDEMP-EXP", new BulkConsentContext("CONS-IDEMP-EXP", "TPP-001",
                Set.of("INITIATEBULKPAYMENTS"), Instant.now(CLOCK), true));
        assertThat(processor.processNextBatch()).isZero();
        assertThat(status(service, expiring.fileId()).status()).isEqualTo(BulkFileStatus.STOPPED);
    }

    @Test
    void processesLargeFilesInBoundedBatches() {
        BulkPaymentService service = service(settings(2));
        BulkFileProcessingService processor = processor(settings(2));

        BulkUploadResult upload = service.submitFile(command("IDEMP-050", validCsv(
                "INS-1," + IBAN + ",10.00",
                "INS-2," + IBAN + ",20.00",
                "INS-3,AE000,30.00",
                "INS-4," + IBAN + ",40.00",
                "INS-5," + IBAN + ",50.00"), BulkIntegrityMode.PARTIAL_REJECTION));

        assertThat(processor.processNextBatch()).isEqualTo(2);
        assertThat(status(service, upload.fileId()).processedCount()).isEqualTo(2);
        assertThat(processor.processNextBatch()).isEqualTo(2);
        assertThat(status(service, upload.fileId()).status()).isEqualTo(BulkFileStatus.PROCESSING);
        assertThat(processor.processNextBatch()).isEqualTo(1);
        assertThat(processor.processNextBatch()).isZero();

        BulkFile done = status(service, upload.fileId());
        assertThat(done.status()).isEqualTo(BulkFileStatus.VALIDATED);
        assertThat(done.acceptedCount()).isEqualTo(4);
        assertThat(done.acceptedAmount()).isEqualTo(Money.of("120.00", "AED"));
        assertThat(itemPort.processed.get(upload.fileId())).containsExactlyInAnyOrder(1, 2, 3, 4, 5);
        assertThat(publisher.published).singleElement().isInstanceOf(BulkFileAccepted.class);
    }

    @Test
    void processorFailsLoudlyWhenItemsAreMissing() {
        BulkFileProcessingService processor = processor(settings(10));
        BulkUploadResult upload = service(settings(10))
                .submitFile(command("IDEMP-060", validCsv("INS-1," + IBAN + ",10.00"), BulkIntegrityMode.PARTIAL_REJECTION));
        itemPort.items.remove(upload.fileId());

        assertThatThrownBy(processor::processNextBatch)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("none is stored")
                .message().doesNotContain(upload.fileId());
    }

    @Test
    void shouldReturnIdempotentReplayForSamePayloadAndConflictForDifferentPayload() {
        BulkPaymentService service = service(settings(2));

        String content = validCsv("INS-1," + IBAN + ",10.00");
        BulkUploadResult first = service.submitFile(command("IDEMP-100", content, BulkIntegrityMode.PARTIAL_REJECTION));
        BulkUploadResult replay = service.submitFile(command("IDEMP-100", content, BulkIntegrityMode.PARTIAL_REJECTION));

        assertThat(replay.fileId()).isEqualTo(first.fileId());
        assertThat(replay.idempotencyReplay()).isTrue();
        assertThat(filePort.data).hasSize(1);
        assertThat(publisher.published).hasSize(1);

        String changed = validCsv("INS-1," + IBAN + ",11.00");
        assertThatThrownBy(() -> service.submitFile(command("IDEMP-100", changed, BulkIntegrityMode.PARTIAL_REJECTION)))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("Idempotency conflict");
    }

    @Test
    void uploadThatLosesTheIdempotencyRaceAnswersAsReplayOfTheWinner() {
        BulkPaymentService service = service(settings(2));
        String content = validCsv("INS-1," + IBAN + ",10.00");
        BulkUploadResult winner = service.submitFile(command("IDEMP-150", content, BulkIntegrityMode.PARTIAL_REJECTION));
        idempotencyPort.hideOnNextFind = true;

        BulkUploadResult loser = service.submitFile(command("IDEMP-150", content, BulkIntegrityMode.PARTIAL_REJECTION));

        assertThat(loser.fileId()).isEqualTo(winner.fileId());
        assertThat(loser.idempotencyReplay()).isTrue();
        assertThat(filePort.data).hasSize(1);
    }

    @Test
    void shouldRejectInvalidPayloadCases() {
        BulkPaymentService service = service(settings(2));

        assertThatThrownBy(() -> service.submitFile(command("IDEMP-200", validCsv(), BulkIntegrityMode.PARTIAL_REJECTION)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Empty Payload");

        String malformed = "bad_header\nINS-1," + IBAN + ",10.00";
        assertThatThrownBy(() -> service.submitFile(command("IDEMP-201", malformed, BulkIntegrityMode.PARTIAL_REJECTION)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Schema Validation Failed");

        String content = validCsv("INS-1," + IBAN + ",10.00");
        assertThatThrownBy(() -> service.submitFile(new SubmitBulkFileCommand(
                "TPP-001", "CONS-BULK-001", "IDEMP-202", "payroll.csv", content, "wrong-hash", "AED",
                BulkIntegrityMode.PARTIAL_REJECTION, "ix-1")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Integrity Failure");

        assertThat(filePort.data).isEmpty();
        assertThat(publisher.published).isEmpty();
    }

    @Test
    void amountsUseTheRequestedCurrencyAndItsMinorUnits() {
        BulkPaymentService service = service(settings(10));

        BulkUploadResult kwd = service.submitFile(command("IDEMP-CUR-1",
                validCsv("INS-1," + IBAN + ",1.234"), BulkIntegrityMode.PARTIAL_REJECTION, "KWD"));
        assertThat(filePort.data.get(kwd.fileId()).totalAmount()).isEqualTo(Money.of("1.234", "KWD"));
        assertThat(((BulkFileAccepted) publisher.published.get(0)).totalAmount()).isEqualTo(Money.of("1.234", "KWD"));

        assertThatThrownBy(() -> service.submitFile(command("IDEMP-CUR-2",
                validCsv("INS-1," + IBAN + ",10.5"), BulkIntegrityMode.PARTIAL_REJECTION, "JPY")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Amount Precision Exceeds Currency Minor Units");
        assertThatThrownBy(() -> service.submitFile(command("IDEMP-CUR-3",
                validCsv("INS-1," + IBAN + ",10.00"), BulkIntegrityMode.PARTIAL_REJECTION, "XAU")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Unsupported Currency");
        assertThat(filePort.data).hasSize(1);
    }

    @Test
    void shouldApplyPartialAndFullRejectionPolicies() {
        BulkPaymentService service = service(settings(10));
        BulkFileProcessingService processor = processor(settings(10));

        String mixed = validCsv("INS-1," + IBAN + ",10.00", "INS-2,AE000,20.00");

        BulkUploadResult partial = service.submitFile(command("IDEMP-300", mixed, BulkIntegrityMode.PARTIAL_REJECTION));
        processor.processNextBatch();
        BulkFileReport partialReport = report(service, partial.fileId());

        assertThat(partialReport.status()).isEqualTo(BulkFileStatus.VALIDATED);
        assertThat(partialReport.acceptedCount()).isEqualTo(1);
        assertThat(partialReport.rejectedCount()).isEqualTo(1);

        BulkUploadResult full = service.submitFile(command("IDEMP-301", mixed, BulkIntegrityMode.FULL_REJECTION));
        assertThat(full.acceptedCount()).isZero();
        processor.processNextBatch();
        BulkFileReport fullReport = report(service, full.fileId());

        assertThat(fullReport.status()).isEqualTo(BulkFileStatus.REJECTED);
        assertThat(fullReport.acceptedCount()).isZero();
        assertThat(fullReport.rejectedCount()).isEqualTo(2);
        assertThat(fullReport.items()).extracting(BulkItemResult::status)
                .containsOnly(BulkItemStatus.REJECTED);
        assertThat(publisher.published).last().isInstanceOf(BulkFileRejected.class);
    }

    @Test
    void shouldEnforceConsentAccess() {
        BulkPaymentService service = service(settings(2));
        String content = validCsv("INS-1," + IBAN + ",10.00");

        assertForbidden(service, "CONS-MISSING", content, "Consent not found");

        consentPort.data.put("CONS-EXPIRED", new BulkConsentContext("CONS-EXPIRED", "TPP-001",
                Set.of("INITIATEBULKPAYMENTS"), Instant.parse("2026-02-01T00:00:00Z"), true));
        assertForbidden(service, "CONS-EXPIRED", content, "expired");

        consentPort.data.put("CONS-OTHER-TPP", new BulkConsentContext("CONS-OTHER-TPP", "TPP-999",
                Set.of("INITIATEBULKPAYMENTS"), Instant.parse("2099-01-01T00:00:00Z"), true));
        assertForbidden(service, "CONS-OTHER-TPP", content, "participant mismatch");

        consentPort.data.put("CONS-REVOKED", new BulkConsentContext("CONS-REVOKED", "TPP-001",
                Set.of("INITIATEBULKPAYMENTS"), Instant.parse("2099-01-01T00:00:00Z"), false));
        assertForbidden(service, "CONS-REVOKED", content, "not authorised");

        consentPort.data.put("CONS-RO", new BulkConsentContext("CONS-RO", "TPP-001",
                Set.of("read-accounts"), Instant.parse("2099-01-01T00:00:00Z"), true));
        assertForbidden(service, "CONS-RO", content, "Required scope missing: INITIATEBULKPAYMENTS");
    }

    @Test
    void anotherTppCannotReadTheFileOrItsReport() {
        BulkPaymentService service = service(settings(2));
        BulkUploadResult upload = service.submitFile(command("IDEMP-450",
                validCsv("INS-1," + IBAN + ",10.00"), BulkIntegrityMode.PARTIAL_REJECTION));

        assertThatThrownBy(() -> service.getFileStatus(new GetBulkFileStatusQuery(upload.fileId(), "TPP-999", "ix-1")))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.getFileReport(new GetBulkFileReportQuery(upload.fileId(), "TPP-999", "ix-1")))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void shouldRejectTooLargePayloadAndUnknownResources() {
        BulkPaymentService service = service(new BulkSettings(Duration.ofHours(24), Duration.ofSeconds(30), 10L, 2));

        String content = validCsv("INS-1," + IBAN + ",10.00");
        assertThatThrownBy(() -> service.submitFile(command("IDEMP-500", content, BulkIntegrityMode.PARTIAL_REJECTION)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Payload Too Large");

        assertThat(service.getFileStatus(new GetBulkFileStatusQuery("FILE-404", "TPP-001", "ix-1"))).isEmpty();
        assertThat(service.getFileReport(new GetBulkFileReportQuery("FILE-404", "TPP-001", "ix-1"))).isEmpty();
    }

    @Test
    void cachesOnlyTerminalReports() {
        BulkPaymentService service = service(settings(2));
        BulkFileProcessingService processor = processor(settings(2));

        BulkUploadResult validated = service.submitFile(command("IDEMP-600",
                validCsv("INS-1," + IBAN + ",10.00"), BulkIntegrityMode.PARTIAL_REJECTION));
        assertThat(report(service, validated.fileId()).status()).isEqualTo(BulkFileStatus.PROCESSING);
        processor.processNextBatch();
        // Validated files will still move once the hand-off exists, so their report is not cached.
        assertThat(report(service, validated.fileId()).status()).isEqualTo(BulkFileStatus.VALIDATED);
        assertThat(cachePort.reportCache).isEmpty();

        BulkUploadResult rejected = service.submitFile(command("IDEMP-601",
                validCsv("INS-1,AE000,10.00"), BulkIntegrityMode.PARTIAL_REJECTION));
        processor.processNextBatch();
        assertThat(report(service, rejected.fileId()).status()).isEqualTo(BulkFileStatus.REJECTED);
        assertThat(cachePort.reportCache).hasSize(1);

        itemPort.items.clear();
        assertThat(report(service, rejected.fileId()).items()).hasSize(1);
    }

    @Test
    void shouldThrowWhenIdempotencyPointsToMissingFile() {
        BulkPaymentService service = service(settings(2));

        String content = validCsv("INS-1," + IBAN + ",10.00");
        String requestHash = command("IDEMP-700", content, BulkIntegrityMode.PARTIAL_REJECTION).requestHash();
        idempotencyPort.records.put("IDEMP-700:TPP-001", new BulkIdempotencyRecord("IDEMP-700", "TPP-001",
                requestHash, "FILE-404", BulkFileStatus.PROCESSING, Instant.parse("2026-02-10T00:00:00Z")));

        assertThatThrownBy(() -> service.submitFile(command("IDEMP-700", content, BulkIntegrityMode.PARTIAL_REJECTION)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Bulk file not found");
    }

    private static void assertForbidden(BulkPaymentService service, String consentId, String content, String message) {
        assertThatThrownBy(() -> service.submitFile(new SubmitBulkFileCommand("TPP-001", consentId,
                "IDEMP-" + consentId, "payroll.csv", content, sha256(content), "AED", BulkIntegrityMode.PARTIAL_REJECTION,
                "ix-1")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining(message);
    }

    private static BulkFile status(BulkPaymentService service, String fileId) {
        return service.getFileStatus(new GetBulkFileStatusQuery(fileId, "TPP-001", "ix-1")).orElseThrow();
    }

    private static BulkFileReport report(BulkPaymentService service, String fileId) {
        return service.getFileReport(new GetBulkFileReportQuery(fileId, "TPP-001", "ix-1")).orElseThrow();
    }

    private static SubmitBulkFileCommand command(String idempotencyKey, String content, BulkIntegrityMode mode) {
        return command(idempotencyKey, content, mode, "AED");
    }

    private static SubmitBulkFileCommand command(String idempotencyKey, String content, BulkIntegrityMode mode,
                                                 String currency) {
        return command("CONS-" + idempotencyKey, idempotencyKey, content, mode, currency);
    }

    /** One consent per key by default: a bulk consent authorises a single file. */
    private static SubmitBulkFileCommand command(String consentId, String idempotencyKey, String content,
                                                 BulkIntegrityMode mode, String currency) {
        return new SubmitBulkFileCommand("TPP-001", consentId, idempotencyKey, "payroll.csv", content,
                sha256(content), currency, mode, "ix-1");
    }

    private static String validCsv(String... rows) {
        StringBuilder builder = new StringBuilder("instruction_id,payee_iban,amount");
        for (String row : rows) {
            builder.append('\n').append(row);
        }
        return builder.toString();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to hash payload", exception);
        }
    }

    private static BulkSettings settings(int batchSize) {
        return new BulkSettings(Duration.ofHours(24), Duration.ofSeconds(30), 10_000_000L, batchSize);
    }

    private BulkPaymentService service(BulkSettings settings) {
        return new BulkPaymentService(consentPort, bindingPort, filePort, itemPort, idempotencyPort, cachePort,
                publisher, settings, CLOCK);
    }

    private BulkFileProcessingService processor(BulkSettings settings) {
        return new BulkFileProcessingService(filePort, itemPort, consentPort, publisher, settings, CLOCK);
    }

    private static final class TestConsentPort implements BulkConsentPort {
        private final Map<String, BulkConsentContext> data = new ConcurrentHashMap<>();
        private final Map<String, Integer> reads = new ConcurrentHashMap<>();
        private final Set<String> missing = new HashSet<>();
        private boolean failNextRead;

        private TestConsentPort() {
            data.put("CONS-BULK-001", new BulkConsentContext("CONS-BULK-001", "TPP-001", Set.of("INITIATEBULKPAYMENTS"),
                    Instant.parse("2099-01-01T00:00:00Z"), true));
        }

        @Override
        public Optional<BulkConsentContext> findById(String consentId) {
            reads.merge(consentId, 1, Integer::sum);
            if (failNextRead) {
                failNextRead = false;
                throw new IllegalStateException("consent service unavailable");
            }
            if (missing.contains(consentId)) {
                return Optional.empty();
            }
            if (consentId.startsWith("CONS-IDEMP-")) {
                return Optional.of(data.computeIfAbsent(consentId, id -> new BulkConsentContext(id, "TPP-001",
                        Set.of("INITIATEBULKPAYMENTS"), Instant.parse("2099-01-01T00:00:00Z"), true)));
            }
            return Optional.ofNullable(data.get(consentId));
        }
    }

    /** Stores files as the database would: a fresh copy per read, version bumped per save. */
    private static final class TestFilePort implements BulkFilePort {
        private final Map<String, BulkFile> data = new LinkedHashMap<>();

        @Override
        public BulkFile save(BulkFile file) {
            BulkFile stored = data.get(file.fileId());
            long nextVersion = 0L;
            if (stored != null) {
                if (stored.version() != file.version()) {
                    throw new IllegalStateException("optimistic lock");
                }
                nextVersion = stored.version() + 1;
            }
            data.put(file.fileId(), copy(file, nextVersion));
            return file;
        }

        @Override
        public Optional<BulkFile> findById(String fileId) {
            return Optional.ofNullable(data.get(fileId)).map(file -> copy(file, file.version()));
        }

        @Override
        public Optional<BulkFile> claimNextProcessing() {
            return data.values().stream()
                    .filter(file -> file.status() == BulkFileStatus.PROCESSING)
                    .min(Comparator.comparing(BulkFile::createdAt))
                    .map(file -> copy(file, file.version()));
        }

        private static BulkFile copy(BulkFile f, long version) {
            return BulkFile.rehydrate(f.fileId(), f.consentId(), f.tppId(), f.idempotencyKey(), f.requestHash(),
                    f.fileName(), f.integrityMode(), f.status(), f.targetStatus(), f.processedCount(),
                    f.totalCount(), f.acceptedCount(), f.rejectedCount(), f.totalAmount(), f.acceptedAmount(),
                    f.createdAt(), f.processedAt(), version);
        }
    }

    private static final class TestItemPort implements BulkItemPort {
        private final Map<String, List<BulkItemResult>> items = new ConcurrentHashMap<>();
        private final Map<String, Set<Integer>> processed = new ConcurrentHashMap<>();

        @Override
        public void saveAll(String fileId, List<BulkItemResult> fileItems) {
            items.put(fileId, new ArrayList<>(fileItems));
        }

        @Override
        public List<BulkItemResult> findByFileId(String fileId) {
            return List.copyOf(items.getOrDefault(fileId, List.of()));
        }

        @Override
        public List<BulkItemResult> findUnprocessed(String fileId, int limit) {
            Set<Integer> done = processed.getOrDefault(fileId, Set.of());
            return items.getOrDefault(fileId, List.of()).stream()
                    .filter(item -> !done.contains(item.lineNumber()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public int markProcessed(String fileId, Collection<Integer> lineNumbers, Instant processedAt) {
            processed.computeIfAbsent(fileId, key -> new HashSet<>()).addAll(lineNumbers);
            return lineNumbers.size();
        }
    }

    private static final class TestIdempotencyPort implements BulkIdempotencyPort {
        private final Map<String, BulkIdempotencyRecord> records = new ConcurrentHashMap<>();
        private boolean hideOnNextFind;

        @Override
        public Optional<BulkIdempotencyRecord> find(String idempotencyKey, String tppId, Instant now) {
            if (hideOnNextFind) {
                // Simulates the concurrent winner not being visible yet at the first lookup.
                hideOnNextFind = false;
                return Optional.empty();
            }
            BulkIdempotencyRecord record = records.get(idempotencyKey + ':' + tppId);
            return record == null || !record.isActive(now) ? Optional.empty() : Optional.of(record);
        }

        @Override
        public boolean reserve(BulkIdempotencyRecord record, Instant now) {
            String key = record.idempotencyKey() + ':' + record.tppId();
            BulkIdempotencyRecord existing = records.get(key);
            if (existing != null && existing.isActive(now)) {
                return false;
            }
            records.put(key, record);
            return true;
        }
    }

    private static final class TestBindingPort implements BulkConsentBindingPort {
        private final Map<String, BulkConsentBinding> bindings = new ConcurrentHashMap<>();

        @Override
        public boolean bind(BulkConsentBinding binding) {
            return bindings.putIfAbsent(binding.consentId(), binding) == null;
        }

        @Override
        public Optional<BulkConsentBinding> findByConsentId(String consentId) {
            return Optional.ofNullable(bindings.get(consentId));
        }
    }

    private static final class TestCachePort implements BulkCachePort {
        private final Map<String, BulkFileReport> reportCache = new ConcurrentHashMap<>();

        @Override
        public Optional<BulkFileReport> getReport(String key, Instant now) {
            return Optional.ofNullable(reportCache.get(key));
        }

        @Override
        public void putReport(String key, BulkFileReport report, Instant expiresAt) {
            reportCache.put(key, report);
        }
    }

    private static final class RecordingPublisher implements BulkFileEventPort {
        private final List<BulkFileEvent> published = new ArrayList<>();

        @Override
        public void publish(BulkFile file, List<BulkFileEvent> events) {
            published.addAll(events);
        }
    }
}
