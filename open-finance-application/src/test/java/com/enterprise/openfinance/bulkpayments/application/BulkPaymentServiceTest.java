package com.enterprise.openfinance.bulkpayments.application;

import com.enterprise.openfinance.bulkpayments.domain.command.SubmitBulkFileCommand;
import com.enterprise.openfinance.bulkpayments.domain.exception.BusinessRuleViolationException;
import com.enterprise.openfinance.bulkpayments.domain.exception.ForbiddenException;
import com.enterprise.openfinance.bulkpayments.domain.exception.IdempotencyConflictException;
import com.enterprise.openfinance.bulkpayments.domain.exception.ResourceNotFoundException;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkConsentContext;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkFile;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkFileReport;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkFileStatus;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkIdempotencyRecord;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkIntegrityMode;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkSettings;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkUploadResult;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkCachePort;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkConsentPort;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkFilePort;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkIdempotencyPort;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkReportPort;
import com.enterprise.openfinance.bulkpayments.domain.query.GetBulkFileReportQuery;
import com.enterprise.openfinance.bulkpayments.domain.query.GetBulkFileStatusQuery;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class BulkPaymentServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-02-09T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void shouldAcceptValidUploadAndProgressStatus() {
        BulkPaymentService service = service(new TestConsentPort(), new TestFilePort(), new TestReportPort(), new TestIdempotencyPort(), new TestCachePort());

        String content = validCsv("INS-1,AE120001000000000000000001,10.00");
        BulkUploadResult upload = service.submitFile(new SubmitBulkFileCommand(
                "TPP-001",
                "CONS-BULK-001",
                "IDEMP-001",
                "payroll.csv",
                content,
                sha256(content),
                BulkIntegrityMode.PARTIAL_REJECTION,
                "ix-1"
        ));

        assertThat(upload.status()).isEqualTo(BulkFileStatus.PROCESSING);
        assertThat(upload.idempotencyReplay()).isFalse();

        BulkFile statusFirst = service.getFileStatus(new GetBulkFileStatusQuery(upload.fileId(), "TPP-001", "ix-1")).orElseThrow();
        BulkFile statusSecond = service.getFileStatus(new GetBulkFileStatusQuery(upload.fileId(), "TPP-001", "ix-1")).orElseThrow();

        assertThat(statusFirst.status()).isEqualTo(BulkFileStatus.PROCESSING);
        assertThat(statusSecond.status()).isEqualTo(BulkFileStatus.COMPLETED);

        BulkFileReport report = service.getFileReport(new GetBulkFileReportQuery(upload.fileId(), "TPP-001", "ix-1")).orElseThrow();
        assertThat(report.status()).isEqualTo(BulkFileStatus.COMPLETED);
        assertThat(report.acceptedCount()).isEqualTo(1);
        assertThat(report.rejectedCount()).isEqualTo(0);
    }

    @Test
    void shouldReturnIdempotentReplayForSamePayloadAndConflictForDifferentPayload() {
        BulkPaymentService service = service(new TestConsentPort(), new TestFilePort(), new TestReportPort(), new TestIdempotencyPort(), new TestCachePort());

        String content = validCsv("INS-1,AE120001000000000000000001,10.00");
        BulkUploadResult first = service.submitFile(command("IDEMP-100", content, BulkIntegrityMode.PARTIAL_REJECTION));
        BulkUploadResult replay = service.submitFile(command("IDEMP-100", content, BulkIntegrityMode.PARTIAL_REJECTION));

        assertThat(replay.fileId()).isEqualTo(first.fileId());
        assertThat(replay.idempotencyReplay()).isTrue();

        String changed = validCsv("INS-1,AE120001000000000000000001,11.00");
        assertThatThrownBy(() -> service.submitFile(command("IDEMP-100", changed, BulkIntegrityMode.PARTIAL_REJECTION)))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("Idempotency conflict");
    }

    @Test
    void shouldRejectInvalidPayloadCases() {
        BulkPaymentService service = service(new TestConsentPort(), new TestFilePort(), new TestReportPort(), new TestIdempotencyPort(), new TestCachePort());

        assertThatThrownBy(() -> service.submitFile(command("IDEMP-200", validCsv(), BulkIntegrityMode.PARTIAL_REJECTION)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Empty Payload");

        String malformed = "bad_header\nINS-1,AE120001000000000000000001,10.00";
        assertThatThrownBy(() -> service.submitFile(command("IDEMP-201", malformed, BulkIntegrityMode.PARTIAL_REJECTION)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Schema Validation Failed");

        String content = validCsv("INS-1,AE120001000000000000000001,10.00");
        assertThatThrownBy(() -> service.submitFile(new SubmitBulkFileCommand(
                "TPP-001",
                "CONS-BULK-001",
                "IDEMP-202",
                "payroll.csv",
                content,
                "wrong-hash",
                BulkIntegrityMode.PARTIAL_REJECTION,
                "ix-1"
        ))).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Integrity Failure");
    }

    @Test
    void shouldApplyPartialAndFullRejectionPolicies() {
        BulkPaymentService service = service(new TestConsentPort(), new TestFilePort(), new TestReportPort(), new TestIdempotencyPort(), new TestCachePort());

        String mixed = validCsv(
                "INS-1,AE120001000000000000000001,10.00",
                "INS-2,AE000,20.00"
        );

        BulkUploadResult partial = service.submitFile(command("IDEMP-300", mixed, BulkIntegrityMode.PARTIAL_REJECTION));
        service.getFileStatus(new GetBulkFileStatusQuery(partial.fileId(), "TPP-001", "ix-1"));
        service.getFileStatus(new GetBulkFileStatusQuery(partial.fileId(), "TPP-001", "ix-1"));
        BulkFileReport partialReport = service.getFileReport(new GetBulkFileReportQuery(partial.fileId(), "TPP-001", "ix-1")).orElseThrow();

        assertThat(partialReport.status()).isEqualTo(BulkFileStatus.PARTIALLY_ACCEPTED);
        assertThat(partialReport.acceptedCount()).isEqualTo(1);
        assertThat(partialReport.rejectedCount()).isEqualTo(1);

        BulkUploadResult full = service.submitFile(command("IDEMP-301", mixed, BulkIntegrityMode.FULL_REJECTION));
        service.getFileStatus(new GetBulkFileStatusQuery(full.fileId(), "TPP-001", "ix-1"));
        service.getFileStatus(new GetBulkFileStatusQuery(full.fileId(), "TPP-001", "ix-1"));
        BulkFileReport fullReport = service.getFileReport(new GetBulkFileReportQuery(full.fileId(), "TPP-001", "ix-1")).orElseThrow();

        assertThat(fullReport.status()).isEqualTo(BulkFileStatus.REJECTED);
        assertThat(fullReport.acceptedCount()).isEqualTo(0);
        assertThat(fullReport.rejectedCount()).isEqualTo(2);
    }

    @Test
    void shouldEnforceConsentAccess() {
        TestConsentPort consentPort = new TestConsentPort();
        BulkPaymentService service = service(consentPort, new TestFilePort(), new TestReportPort(), new TestIdempotencyPort(), new TestCachePort());

        String content = validCsv("INS-1,AE120001000000000000000001,10.00");

        assertThatThrownBy(() -> service.submitFile(new SubmitBulkFileCommand(
                "TPP-001",
                "CONS-MISSING",
                "IDEMP-400",
                "payroll.csv",
                content,
                sha256(content),
                BulkIntegrityMode.PARTIAL_REJECTION,
                "ix-1"
        ))).isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Consent not found");

        consentPort.data.put("CONS-EXPIRED", new BulkConsentContext("CONS-EXPIRED", "TPP-001", Set.of("bulk-payment"), Instant.parse("2026-02-01T00:00:00Z")));

        assertThatThrownBy(() -> service.submitFile(new SubmitBulkFileCommand(
                "TPP-001",
                "CONS-EXPIRED",
                "IDEMP-401",
                "payroll.csv",
                content,
                sha256(content),
                BulkIntegrityMode.PARTIAL_REJECTION,
                "ix-1"
        ))).isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void shouldRejectTooLargePayloadAndUnknownResources() {
        BulkPaymentService service = service(new TestConsentPort(), new TestFilePort(), new TestReportPort(), new TestIdempotencyPort(), new TestCachePort(),
                new BulkSettings(Duration.ofHours(24), Duration.ofSeconds(30), 10L, 2));

        String content = validCsv("INS-1,AE120001000000000000000001,10.00");
        assertThatThrownBy(() -> service.submitFile(command("IDEMP-500", content, BulkIntegrityMode.PARTIAL_REJECTION)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Payload Too Large");

        assertThat(service.getFileStatus(new GetBulkFileStatusQuery("FILE-404", "TPP-001", "ix-1"))).isEmpty();
        assertThat(service.getFileReport(new GetBulkFileReportQuery("FILE-404", "TPP-001", "ix-1"))).isEmpty();
    }

    @Test
    void shouldUseReportCacheAfterFirstRead() {
        TestCachePort cachePort = new TestCachePort();
        BulkPaymentService service = service(new TestConsentPort(), new TestFilePort(), new TestReportPort(), new TestIdempotencyPort(), cachePort);

        String content = validCsv("INS-1,AE120001000000000000000001,10.00");
        BulkUploadResult upload = service.submitFile(command("IDEMP-600", content, BulkIntegrityMode.PARTIAL_REJECTION));
        service.getFileStatus(new GetBulkFileStatusQuery(upload.fileId(), "TPP-001", "ix-1"));
        service.getFileStatus(new GetBulkFileStatusQuery(upload.fileId(), "TPP-001", "ix-1"));

        assertThat(service.getFileReport(new GetBulkFileReportQuery(upload.fileId(), "TPP-001", "ix-1"))).isPresent();
        assertThat(service.getFileReport(new GetBulkFileReportQuery(upload.fileId(), "TPP-001", "ix-1"))).isPresent();

        assertThat(cachePort.reportCache).isNotEmpty();
    }

    @Test
    void shouldThrowWhenIdempotencyPointsToMissingFile() {
        TestIdempotencyPort idempotencyPort = new TestIdempotencyPort();
        BulkPaymentService service = service(new TestConsentPort(), new TestFilePort(), new TestReportPort(), idempotencyPort, new TestCachePort());

        String content = validCsv("INS-1,AE120001000000000000000001,10.00");
        String requestHash = new SubmitBulkFileCommand(
                "TPP-001", "CONS-BULK-001", "IDEMP-700", "payroll.csv", content, sha256(content), BulkIntegrityMode.PARTIAL_REJECTION, "ix-1"
        ).requestHash();
        idempotencyPort.records.put("IDEMP-700:TPP-001", new BulkIdempotencyRecord(
                "IDEMP-700",
                "TPP-001",
                requestHash,
                "FILE-404",
                BulkFileStatus.PROCESSING,
                Instant.parse("2026-02-10T00:00:00Z")
        ));

        assertThatThrownBy(() -> service.submitFile(command("IDEMP-700", content, BulkIntegrityMode.PARTIAL_REJECTION)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Bulk file not found");
    }

    private static SubmitBulkFileCommand command(String idempotencyKey, String content, BulkIntegrityMode mode) {
        return new SubmitBulkFileCommand(
                "TPP-001",
                "CONS-BULK-001",
                idempotencyKey,
                "payroll.csv",
                content,
                sha256(content),
                mode,
                "ix-1"
        );
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

    private static BulkPaymentService service(BulkConsentPort consentPort,
                                              BulkFilePort filePort,
                                              BulkReportPort reportPort,
                                              BulkIdempotencyPort idempotencyPort,
                                              BulkCachePort cachePort) {
        return service(consentPort, filePort, reportPort, idempotencyPort, cachePort,
                new BulkSettings(Duration.ofHours(24), Duration.ofSeconds(30), 10_000_000L, 2));
    }

    private static BulkPaymentService service(BulkConsentPort consentPort,
                                              BulkFilePort filePort,
                                              BulkReportPort reportPort,
                                              BulkIdempotencyPort idempotencyPort,
                                              BulkCachePort cachePort,
                                              BulkSettings settings) {
        return new BulkPaymentService(
                consentPort,
                filePort,
                reportPort,
                idempotencyPort,
                cachePort,
                settings,
                CLOCK
        );
    }

    private static final class TestConsentPort implements BulkConsentPort {
        private final Map<String, BulkConsentContext> data = new ConcurrentHashMap<>();

        private TestConsentPort() {
            data.put("CONS-BULK-001", new BulkConsentContext(
                    "CONS-BULK-001",
                    "TPP-001",
                    Set.of("bulk-payment"),
                    Instant.parse("2099-01-01T00:00:00Z")
            ));
        }

        @Override
        public Optional<BulkConsentContext> findById(String consentId) {
            return Optional.ofNullable(data.get(consentId));
        }
    }

    private static final class TestFilePort implements BulkFilePort {
        private final Map<String, BulkFile> data = new ConcurrentHashMap<>();
        private final AtomicInteger saveCount = new AtomicInteger();

        @Override
        public BulkFile save(BulkFile file) {
            saveCount.incrementAndGet();
            data.put(file.fileId(), file);
            return file;
        }

        @Override
        public Optional<BulkFile> findById(String fileId) {
            return Optional.ofNullable(data.get(fileId));
        }
    }

    private static final class TestReportPort implements BulkReportPort {
        private final Map<String, BulkFileReport> data = new ConcurrentHashMap<>();

        @Override
        public BulkFileReport save(BulkFileReport report) {
            data.put(report.fileId(), report);
            return report;
        }

        @Override
        public Optional<BulkFileReport> findByFileId(String fileId) {
            return Optional.ofNullable(data.get(fileId));
        }
    }

    private static final class TestIdempotencyPort implements BulkIdempotencyPort {
        private final Map<String, BulkIdempotencyRecord> records = new ConcurrentHashMap<>();

        @Override
        public Optional<BulkIdempotencyRecord> find(String idempotencyKey, String tppId, Instant now) {
            String key = idempotencyKey + ':' + tppId;
            BulkIdempotencyRecord record = records.get(key);
            if (record == null || !record.isActive(now)) {
                records.remove(key);
                return Optional.empty();
            }
            return Optional.of(record);
        }

        @Override
        public void save(BulkIdempotencyRecord record) {
            records.put(record.idempotencyKey() + ':' + record.tppId(), record);
        }
    }

    private static final class TestCachePort implements BulkCachePort {
        private final Map<String, CacheItem<BulkFileReport>> reportCache = new ConcurrentHashMap<>();

        @Override
        public Optional<BulkFileReport> getReport(String key, Instant now) {
            CacheItem<BulkFileReport> item = reportCache.get(key);
            if (item == null || !item.expiresAt().isAfter(now)) {
                reportCache.remove(key);
                return Optional.empty();
            }
            return Optional.of(item.value());
        }

        @Override
        public void putReport(String key, BulkFileReport report, Instant expiresAt) {
            reportCache.put(key, new CacheItem<>(report, expiresAt));
        }

        private record CacheItem<T>(T value, Instant expiresAt) {
        }
    }
}
