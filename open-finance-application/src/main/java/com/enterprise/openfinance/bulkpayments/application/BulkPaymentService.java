package com.enterprise.openfinance.bulkpayments.application;

import com.enterprise.openfinance.bulkpayments.domain.port.in.command.SubmitBulkFileCommand;
import com.enterprise.openfinance.bulkpayments.domain.exception.ConsentAlreadyUsedException;
import com.enterprise.openfinance.bulkpayments.domain.exception.ForbiddenException;
import com.enterprise.openfinance.bulkpayments.domain.exception.IdempotencyConflictException;
import com.enterprise.openfinance.bulkpayments.domain.exception.ResourceNotFoundException;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkConsentBinding;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkConsentContext;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkFile;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkFileReport;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkIdempotencyRecord;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkSettings;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkUploadResult;
import com.enterprise.openfinance.bulkpayments.domain.model.ParsedBulkFile;
import com.enterprise.openfinance.bulkpayments.domain.port.in.BulkPaymentUseCase;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkCachePort;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkConsentBindingPort;
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
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

/**
 * Upload, status and report use cases. A consent authorises one file: the upload
 * binds it after the idempotency reservation, so a replay of the same upload is
 * still answered while a second file on the consent is refused.
 *
 * Rules live in {@link BulkFileParser}
 * and {@link BulkFile}; this class loads, calls the domain, saves and
 * publishes the aggregate's events in one transaction.
 */
@Service
public class BulkPaymentService implements BulkPaymentUseCase {

    private final BulkConsentPort consentPort;
    private final BulkConsentBindingPort bindingPort;
    private final BulkFilePort filePort;
    private final BulkItemPort itemPort;
    private final BulkIdempotencyPort idempotencyPort;
    private final BulkCachePort cachePort;
    private final BulkFileEventPort eventPublisher;
    private final BulkSettings settings;
    private final Clock clock;
    private final TransactionOperations transactions;

    public BulkPaymentService(BulkConsentPort consentPort,
                              BulkConsentBindingPort bindingPort,
                              BulkFilePort filePort,
                              BulkItemPort itemPort,
                              BulkIdempotencyPort idempotencyPort,
                              BulkCachePort cachePort,
                              BulkFileEventPort eventPublisher,
                              BulkSettings settings,
                              Clock clock,
                              TransactionOperations transactions) {
        this.consentPort = consentPort;
        this.bindingPort = bindingPort;
        this.filePort = filePort;
        this.itemPort = itemPort;
        this.idempotencyPort = idempotencyPort;
        this.cachePort = cachePort;
        this.eventPublisher = eventPublisher;
        this.settings = settings;
        this.clock = clock;
        this.transactions = transactions;
    }

    /**
     * The idempotency record is read first: a retry of an accepted upload is
     * answered as its replay, whatever has happened to the consent since (used
     * up by this very file, revoked or expired). Only a new upload checks the
     * consent. The consent call, the size and hash checks and the parse run
     * before any transaction, so no database connection is held while the
     * consent service answers or a large file is parsed. The transaction starts
     * at the idempotency reservation and covers the binding, the file, its
     * items and the outbox rows.
     */
    @Override
    public BulkUploadResult submitFile(SubmitBulkFileCommand command) {
        Instant now = Instant.now(clock);
        Optional<BulkUploadResult> replay = lookupIdempotentReplay(command, now);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }

        validateConsent(command.consentId(), command.tppId(), now);
        Currency currency = BulkFileParser.currency(command.currency());
        BulkFileParser.verifyPayload(command.fileContent(), settings.maxFileSizeBytes());
        BulkFileParser.verifyHash(command.fileContent(), command.fileHash());

        ParsedBulkFile parsed = BulkFileParser.parse(command.fileContent(), command.integrityMode(), currency);
        BulkFile file = BulkFile.accept("FILE-BULK-" + UUID.randomUUID(), command.consentId(), command.tppId(),
                command.idempotencyKey(), command.requestHash(), command.fileName(), command.integrityMode(),
                parsed, now);

        return transactions.execute(status -> store(command, parsed, file, now));
    }

    private BulkUploadResult store(SubmitBulkFileCommand command, ParsedBulkFile parsed, BulkFile file, Instant now) {
        boolean reserved = idempotencyPort.reserve(new BulkIdempotencyRecord(
                command.idempotencyKey(), command.tppId(), command.requestHash(), file.fileId(), file.status()), now);
        if (!reserved) {
            // A concurrent upload with the same key committed first: answer as its replay.
            return lookupIdempotentReplay(command, now)
                    .orElseThrow(() -> new IdempotencyConflictException("Idempotency conflict"));
        }

        if (!bindingPort.bind(BulkConsentBinding.of(file, command.fileHash(), now))) {
            // Throwing rolls back the idempotency reservation too: nothing of this upload is kept.
            throw new ConsentAlreadyUsedException("Consent already used for another file");
        }

        filePort.save(file);
        itemPort.saveAll(file.fileId(), parsed.items());
        eventPublisher.publish(file, file.pullDomainEvents());

        return new BulkUploadResult(file.fileId(), file.status(), command.interactionId(), false,
                file.acceptedCount(), file.rejectedCount(), file.createdAt());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BulkFile> getFileStatus(GetBulkFileStatusQuery query) {
        Optional<BulkFile> file = filePort.findById(query.fileId());
        file.ifPresent(found -> ensureFileOwnership(found, query.tppId()));
        return file;
    }

    @Override
    @Transactional(readOnly = true)
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

        BulkFileReport report = BulkFileReport.of(file, itemPort.findByFileId(file.fileId()), now);
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

    /** Every refusal is the same 403: the caller learns nothing about another party's consent. */
    private void validateConsent(String consentId, String tppId, Instant now) {
        boolean usable = consentPort.findById(consentId)
                .filter(consent -> consent.belongsToTpp(tppId))
                .filter(BulkConsentContext::isAuthorized)
                .filter(consent -> consent.isActive(now))
                .filter(BulkConsentContext::allowsBulkInitiation)
                .isPresent();
        if (!usable) {
            throw new ForbiddenException(ForbiddenException.CONSENT_NOT_USABLE);
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
