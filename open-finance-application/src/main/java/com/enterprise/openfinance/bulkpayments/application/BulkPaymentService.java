package com.enterprise.openfinance.bulkpayments.application;

import com.enterprise.openfinance.bulkpayments.domain.port.in.command.SubmitBulkFileCommand;
import com.enterprise.openfinance.bulkpayments.domain.exception.ForbiddenException;
import com.enterprise.openfinance.bulkpayments.domain.exception.IdempotencyConflictException;
import com.enterprise.openfinance.bulkpayments.domain.exception.ResourceNotFoundException;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkConsentContext;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkFile;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkFileReport;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkIdempotencyRecord;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkSettings;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkUploadResult;
import com.enterprise.openfinance.bulkpayments.domain.model.ParsedBulkFile;
import com.enterprise.openfinance.bulkpayments.domain.port.in.BulkPaymentUseCase;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkCachePort;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkConsentPort;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkFileEventPort;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkFilePort;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkIdempotencyPort;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkItemPort;
import com.enterprise.openfinance.bulkpayments.domain.port.in.query.GetBulkFileReportQuery;
import com.enterprise.openfinance.bulkpayments.domain.port.in.query.GetBulkFileStatusQuery;
import com.enterprise.openfinance.bulkpayments.domain.service.BulkFileParser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Upload, status and report use cases. Rules live in {@link BulkFileParser}
 * and {@link BulkFile}; this class loads, calls the domain, saves and
 * publishes the aggregate's events in one transaction.
 */
@Service
@Transactional(readOnly = true)
public class BulkPaymentService implements BulkPaymentUseCase {

    static final String REQUIRED_SCOPE = "bulk-payment";

    private final BulkConsentPort consentPort;
    private final BulkFilePort filePort;
    private final BulkItemPort itemPort;
    private final BulkIdempotencyPort idempotencyPort;
    private final BulkCachePort cachePort;
    private final BulkFileEventPort eventPublisher;
    private final BulkSettings settings;
    private final Clock clock;

    public BulkPaymentService(BulkConsentPort consentPort,
                              BulkFilePort filePort,
                              BulkItemPort itemPort,
                              BulkIdempotencyPort idempotencyPort,
                              BulkCachePort cachePort,
                              BulkFileEventPort eventPublisher,
                              BulkSettings settings,
                              Clock clock) {
        this.consentPort = consentPort;
        this.filePort = filePort;
        this.itemPort = itemPort;
        this.idempotencyPort = idempotencyPort;
        this.cachePort = cachePort;
        this.eventPublisher = eventPublisher;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional
    public BulkUploadResult submitFile(SubmitBulkFileCommand command) {
        Instant now = Instant.now(clock);
        validateConsent(command.consentId(), command.tppId(), now);
        BulkFileParser.verifyPayload(command.fileContent(), settings.maxFileSizeBytes());
        BulkFileParser.verifyHash(command.fileContent(), command.fileHash());

        Optional<BulkUploadResult> replay = lookupIdempotentReplay(command, now);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }

        ParsedBulkFile parsed = BulkFileParser.parse(command.fileContent(), command.integrityMode());
        BulkFile file = BulkFile.accept("FILE-BULK-" + UUID.randomUUID(), command.consentId(), command.tppId(),
                command.idempotencyKey(), command.requestHash(), command.fileName(), command.integrityMode(),
                parsed, now);

        boolean reserved = idempotencyPort.reserve(new BulkIdempotencyRecord(
                command.idempotencyKey(), command.tppId(), command.requestHash(), file.fileId(), file.status(),
                now.plus(settings.idempotencyTtl())), now);
        if (!reserved) {
            // A concurrent upload with the same key committed first: answer as its replay.
            return lookupIdempotentReplay(command, now)
                    .orElseThrow(() -> new IdempotencyConflictException("Idempotency conflict"));
        }

        filePort.save(file);
        itemPort.saveAll(file.fileId(), parsed.items());
        eventPublisher.publish(file, file.pullDomainEvents());

        return new BulkUploadResult(file.fileId(), file.status(), command.interactionId(), false,
                file.acceptedCount(), file.rejectedCount(), file.createdAt());
    }

    @Override
    public Optional<BulkFile> getFileStatus(GetBulkFileStatusQuery query) {
        Optional<BulkFile> file = filePort.findById(query.fileId());
        file.ifPresent(found -> ensureFileOwnership(found, query.tppId()));
        return file;
    }

    @Override
    public Optional<BulkFileReport> getFileReport(GetBulkFileReportQuery query) {
        Instant now = Instant.now(clock);
        String cacheKey = reportCacheKey(query.fileId(), query.tppId());

        Optional<BulkFileReport> cached = cachePort.getReport(cacheKey, now);
        if (cached.isPresent()) {
            return cached;
        }

        Optional<BulkFile> fileOptional = filePort.findById(query.fileId());
        if (fileOptional.isEmpty()) {
            return Optional.empty();
        }
        BulkFile file = fileOptional.orElseThrow();
        ensureFileOwnership(file, query.tppId());

        BulkFileReport report = new BulkFileReport(file.fileId(), file.status(), file.totalCount(),
                file.acceptedCount(), file.rejectedCount(), itemPort.findByFileId(file.fileId()), now);
        if (file.isTerminal()) {
            // A terminal report never changes; a processing one would go stale.
            cachePort.putReport(cacheKey, report, now.plus(settings.cacheTtl()));
        }
        return Optional.of(report);
    }

    private Optional<BulkUploadResult> lookupIdempotentReplay(SubmitBulkFileCommand command, Instant now) {
        return idempotencyPort.find(command.idempotencyKey(), command.tppId(), now)
                .map(record -> {
                    if (!record.requestHash().equals(command.requestHash())) {
                        throw new IdempotencyConflictException("Idempotency conflict");
                    }
                    BulkFile file = filePort.findById(record.fileId())
                            .orElseThrow(() -> new ResourceNotFoundException("Bulk file not found for idempotency record"));
                    return new BulkUploadResult(file.fileId(), file.status(), command.interactionId(), true,
                            file.acceptedCount(), file.rejectedCount(), file.createdAt());
                });
    }

    private void validateConsent(String consentId, String tppId, Instant now) {
        BulkConsentContext consent = consentPort.findById(consentId)
                .orElseThrow(() -> new ForbiddenException("Consent not found"));

        if (!consent.belongsToTpp(tppId)) {
            throw new ForbiddenException("Consent participant mismatch");
        }
        if (!consent.isAuthorized()) {
            throw new ForbiddenException("Consent not authorised");
        }
        if (!consent.isActive(now)) {
            throw new ForbiddenException("Consent expired");
        }
        if (!consent.hasScope(REQUIRED_SCOPE)) {
            throw new ForbiddenException("Required scope missing: " + REQUIRED_SCOPE);
        }
    }

    private static String reportCacheKey(String fileId, String tppId) {
        return "report:" + fileId + ':' + tppId;
    }

    private static void ensureFileOwnership(BulkFile file, String tppId) {
        if (!file.belongsToTpp(tppId)) {
            throw new ForbiddenException("Consent participant mismatch");
        }
    }
}
