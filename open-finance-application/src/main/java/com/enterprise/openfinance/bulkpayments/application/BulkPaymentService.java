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
import com.enterprise.openfinance.bulkpayments.domain.model.BulkItemResult;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkSettings;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkUploadResult;
import com.enterprise.openfinance.bulkpayments.domain.port.in.BulkPaymentUseCase;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkCachePort;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkConsentPort;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkFilePort;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkIdempotencyPort;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkReportPort;
import com.enterprise.openfinance.bulkpayments.domain.query.GetBulkFileReportQuery;
import com.enterprise.openfinance.bulkpayments.domain.query.GetBulkFileStatusQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class BulkPaymentService implements BulkPaymentUseCase {

    private static final String EXPECTED_HEADER = "instruction_id,payee_iban,amount";

    private final BulkConsentPort consentPort;
    private final BulkFilePort filePort;
    private final BulkReportPort reportPort;
    private final BulkIdempotencyPort idempotencyPort;
    private final BulkCachePort cachePort;
    private final BulkSettings settings;
    private final Clock clock;

    public BulkPaymentService(BulkConsentPort consentPort,
                              BulkFilePort filePort,
                              BulkReportPort reportPort,
                              BulkIdempotencyPort idempotencyPort,
                              BulkCachePort cachePort,
                              BulkSettings settings,
                              Clock clock) {
        this.consentPort = consentPort;
        this.filePort = filePort;
        this.reportPort = reportPort;
        this.idempotencyPort = idempotencyPort;
        this.cachePort = cachePort;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional
    public BulkUploadResult submitFile(SubmitBulkFileCommand command) {
        Instant now = Instant.now(clock);
        validateConsent(command.consentId(), command.tppId(), now);
        validatePayload(command.fileContent());
        validateHash(command.fileContent(), command.fileHash());

        Optional<BulkUploadResult> replay = lookupIdempotentReplay(command, now);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }

        ParsedFile parsed = parseCsv(command.fileContent(), command.integrityMode());
        String fileId = "FILE-BULK-" + UUID.randomUUID();

        BulkFile file = new BulkFile(
                fileId,
                command.consentId(),
                command.tppId(),
                command.idempotencyKey(),
                command.requestHash(),
                command.fileName(),
                command.integrityMode(),
                BulkFileStatus.PROCESSING,
                parsed.targetStatus(),
                0,
                parsed.totalCount(),
                parsed.acceptedCount(),
                parsed.rejectedCount(),
                parsed.totalAmount(),
                now,
                null
        );
        filePort.save(file);

        BulkFileReport report = new BulkFileReport(
                file.fileId(),
                parsed.targetStatus(),
                parsed.totalCount(),
                parsed.acceptedCount(),
                parsed.rejectedCount(),
                parsed.items(),
                now
        );
        reportPort.save(report);

        idempotencyPort.save(new BulkIdempotencyRecord(
                command.idempotencyKey(),
                command.tppId(),
                command.requestHash(),
                file.fileId(),
                file.status(),
                now.plus(settings.idempotencyTtl())
        ));

        return new BulkUploadResult(
                file.fileId(),
                file.status(),
                command.interactionId(),
                false,
                file.acceptedCount(),
                file.rejectedCount(),
                file.createdAt()
        );
    }

    @Override
    @Transactional
    public Optional<BulkFile> getFileStatus(GetBulkFileStatusQuery query) {
        Optional<BulkFile> fileOptional = filePort.findById(query.fileId());
        if (fileOptional.isEmpty()) {
            return Optional.empty();
        }

        BulkFile file = fileOptional.orElseThrow();
        ensureFileOwnership(file, query.tppId());

        if (!file.isTerminal()) {
            BulkFile advanced = file.advanceProcessing(settings.statusPollsToComplete(), Instant.now(clock));
            if (!advanced.equals(file)) {
                filePort.save(advanced);
                file = advanced;
            }
        }

        return Optional.of(file);
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

        BulkFileReport report = reportPort.findByFileId(query.fileId())
                .orElseThrow(() -> new ResourceNotFoundException("Bulk report not found"));

        cachePort.putReport(cacheKey, report, now.plus(settings.cacheTtl()));
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

                    return new BulkUploadResult(
                            file.fileId(),
                            file.status(),
                            command.interactionId(),
                            true,
                            file.acceptedCount(),
                            file.rejectedCount(),
                            file.createdAt()
                    );
                });
    }

    private void validateConsent(String consentId, String tppId, Instant now) {
        BulkConsentContext consent = consentPort.findById(consentId)
                .orElseThrow(() -> new ForbiddenException("Consent not found"));

        if (!consent.belongsToTpp(tppId)) {
            throw new ForbiddenException("Consent participant mismatch");
        }
        if (!consent.isActive(now)) {
            throw new ForbiddenException("Consent expired");
        }
        if (!consent.hasScope("bulk-payment")) {
            throw new ForbiddenException("Required scope missing: bulk-payment");
        }
    }

    private void validatePayload(String fileContent) {
        if (fileContent == null || fileContent.isBlank()) {
            throw new BusinessRuleViolationException("Empty Payload");
        }

        long payloadSize = fileContent.getBytes(StandardCharsets.UTF_8).length;
        if (payloadSize > settings.maxFileSizeBytes()) {
            throw new BusinessRuleViolationException("Payload Too Large");
        }
    }

    private static void validateHash(String fileContent, String expectedHash) {
        String computed = sha256(fileContent);
        if (!computed.equals(expectedHash)) {
            throw new BusinessRuleViolationException("Integrity Failure");
        }
    }

    private ParsedFile parseCsv(String fileContent, BulkIntegrityMode mode) {
        String[] lines = fileContent.split("\\r?\\n");
        if (lines.length < 2) {
            throw new BusinessRuleViolationException("Empty Payload");
        }

        String header = lines[0].trim().toLowerCase();
        if (!EXPECTED_HEADER.equals(header)) {
            throw new BusinessRuleViolationException("Schema Validation Failed");
        }

        List<BulkItemResult> parsedItems = new ArrayList<>();
        int accepted = 0;
        int rejected = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;

        int logicalLine = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line == null || line.isBlank()) {
                continue;
            }

            logicalLine++;
            String[] columns = line.split(",", -1);
            if (columns.length != 3) {
                throw new BusinessRuleViolationException("Schema Validation Failed");
            }

            String instructionId = columns[0].trim();
            String payeeIban = columns[1].trim();
            String amountRaw = columns[2].trim();
            if (instructionId.isBlank() || payeeIban.isBlank() || amountRaw.isBlank()) {
                throw new BusinessRuleViolationException("Schema Validation Failed");
            }

            BigDecimal amount;
            try {
                amount = new BigDecimal(amountRaw);
            } catch (NumberFormatException exception) {
                throw new BusinessRuleViolationException("Schema Validation Failed");
            }
            if (amount.signum() <= 0) {
                throw new BusinessRuleViolationException("Schema Validation Failed");
            }

            totalAmount = totalAmount.add(amount);

            if (!isLikelyIban(payeeIban)) {
                parsedItems.add(BulkItemResult.rejected(logicalLine, instructionId, payeeIban, amount, "Invalid IBAN"));
                rejected++;
                continue;
            }

            parsedItems.add(BulkItemResult.accepted(logicalLine, instructionId, payeeIban, amount));
            accepted++;
        }

        int totalCount = accepted + rejected;
        if (totalCount == 0) {
            throw new BusinessRuleViolationException("Empty Payload");
        }

        if (mode == BulkIntegrityMode.FULL_REJECTION && rejected > 0) {
            List<BulkItemResult> rejectedItems = parsedItems.stream()
                    .map(item -> BulkItemResult.rejected(
                            item.lineNumber(),
                            item.instructionId(),
                            item.payeeIban(),
                            item.amount(),
                            item.errorMessage() == null ? "Rejected due to full rejection mode" : item.errorMessage()))
                    .toList();

            return new ParsedFile(totalCount, 0, totalCount, totalAmount, rejectedItems, BulkFileStatus.REJECTED);
        }

        BulkFileStatus targetStatus;
        if (rejected == 0) {
            targetStatus = BulkFileStatus.COMPLETED;
        } else if (accepted == 0) {
            targetStatus = BulkFileStatus.REJECTED;
        } else {
            targetStatus = BulkFileStatus.PARTIALLY_ACCEPTED;
        }

        return new ParsedFile(totalCount, accepted, rejected, totalAmount, parsedItems, targetStatus);
    }

    private static String reportCacheKey(String fileId, String tppId) {
        return "report:" + fileId + ':' + tppId;
    }

    private static void ensureFileOwnership(BulkFile file, String tppId) {
        if (!file.belongsToTpp(tppId)) {
            throw new ForbiddenException("Consent participant mismatch");
        }
    }

    private static boolean isLikelyIban(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toUpperCase();
        if (normalized.length() < 15 || normalized.length() > 34) {
            return false;
        }
        if (!Character.isLetter(normalized.charAt(0)) || !Character.isLetter(normalized.charAt(1))) {
            return false;
        }
        if (!Character.isDigit(normalized.charAt(2)) || !Character.isDigit(normalized.charAt(3))) {
            return false;
        }
        for (int i = 0; i < normalized.length(); i++) {
            if (!Character.isLetterOrDigit(normalized.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to hash payload", exception);
        }
    }

    private record ParsedFile(
            int totalCount,
            int acceptedCount,
            int rejectedCount,
            BigDecimal totalAmount,
            List<BulkItemResult> items,
            BulkFileStatus targetStatus
    ) {
    }
}
