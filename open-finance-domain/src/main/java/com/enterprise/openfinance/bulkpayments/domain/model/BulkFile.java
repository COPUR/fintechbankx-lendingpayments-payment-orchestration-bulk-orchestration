package com.enterprise.openfinance.bulkpayments.domain.model;

import com.enterprise.openfinance.bulkpayments.domain.event.BulkFileAccepted;
import com.enterprise.openfinance.bulkpayments.domain.event.BulkFileCompleted;
import com.enterprise.openfinance.bulkpayments.domain.event.BulkFileEvent;
import com.enterprise.openfinance.bulkpayments.domain.event.BulkFileRejected;
import com.enterprise.openfinance.bulkpayments.domain.exception.BusinessRuleViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root of the bulk-payments context: one uploaded file. Its items
 * live in their own table and are referenced by {@code fileId} and line
 * number; the aggregate keeps the counters and the lifecycle.
 *
 * <pre>
 * accept() ──▶ PROCESSING ──recordProcessedBatch()…──▶ COMPLETED | PARTIALLY_ACCEPTED | REJECTED
 * </pre>
 *
 * The validation outcome of each item is fixed at upload ({@code targetStatus});
 * processing walks the items in bounded batches and the file reaches its
 * target status when the last item has been processed.
 */
public final class BulkFile {

    private final String fileId;
    private final String consentId;
    private final String tppId;
    private final String idempotencyKey;
    private final String requestHash;
    private final String fileName;
    private final BulkIntegrityMode integrityMode;
    private BulkFileStatus status;
    private final BulkFileStatus targetStatus;
    private int processedCount;
    private final int totalCount;
    private final int acceptedCount;
    private final int rejectedCount;
    private final BigDecimal totalAmount;
    private final BigDecimal acceptedAmount;
    private final Instant createdAt;
    private Instant processedAt;
    private final long version;
    private final List<BulkFileEvent> domainEvents = new ArrayList<>();

    private BulkFile(String fileId,
                     String consentId,
                     String tppId,
                     String idempotencyKey,
                     String requestHash,
                     String fileName,
                     BulkIntegrityMode integrityMode,
                     BulkFileStatus status,
                     BulkFileStatus targetStatus,
                     int processedCount,
                     int totalCount,
                     int acceptedCount,
                     int rejectedCount,
                     BigDecimal totalAmount,
                     BigDecimal acceptedAmount,
                     Instant createdAt,
                     Instant processedAt,
                     long version) {
        requireText(fileId, "fileId");
        requireText(consentId, "consentId");
        requireText(tppId, "tppId");
        requireText(idempotencyKey, "idempotencyKey");
        requireText(requestHash, "requestHash");
        requireText(fileName, "fileName");
        if (integrityMode == null) {
            throw new IllegalArgumentException("integrityMode is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (targetStatus == null || !targetStatus.isTerminal()) {
            throw new IllegalArgumentException("targetStatus must be a terminal status");
        }
        if (status.isTerminal() && status != targetStatus) {
            throw new IllegalArgumentException("status must be PROCESSING or equal to targetStatus");
        }
        if (totalCount <= 0) {
            throw new IllegalArgumentException("totalCount must be > 0");
        }
        if (processedCount < 0 || processedCount > totalCount) {
            throw new IllegalArgumentException("processedCount out of range");
        }
        if (status.isTerminal() != (processedCount == totalCount)) {
            throw new IllegalArgumentException("processedCount must equal totalCount exactly when the file is terminal");
        }
        if (acceptedCount < 0 || acceptedCount > totalCount) {
            throw new IllegalArgumentException("acceptedCount out of range");
        }
        if (rejectedCount < 0 || rejectedCount > totalCount) {
            throw new IllegalArgumentException("rejectedCount out of range");
        }
        if (acceptedCount + rejectedCount != totalCount) {
            throw new IllegalArgumentException("acceptedCount + rejectedCount must equal totalCount");
        }
        if (totalAmount == null || totalAmount.signum() <= 0) {
            throw new IllegalArgumentException("totalAmount must be positive");
        }
        if (acceptedAmount == null || acceptedAmount.signum() < 0 || acceptedAmount.compareTo(totalAmount) > 0) {
            throw new IllegalArgumentException("acceptedAmount must be between zero and totalAmount");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt is required");
        }
        if (status.isTerminal() && processedAt == null) {
            throw new IllegalArgumentException("processedAt is required once the file is terminal");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must be >= 0");
        }

        this.fileId = fileId.trim();
        this.consentId = consentId.trim();
        this.tppId = tppId.trim();
        this.idempotencyKey = idempotencyKey.trim();
        this.requestHash = requestHash.trim();
        this.fileName = fileName.trim();
        this.integrityMode = integrityMode;
        this.status = status;
        this.targetStatus = targetStatus;
        this.processedCount = processedCount;
        this.totalCount = totalCount;
        this.acceptedCount = acceptedCount;
        this.rejectedCount = rejectedCount;
        this.totalAmount = totalAmount;
        this.acceptedAmount = acceptedAmount;
        this.createdAt = createdAt;
        this.processedAt = processedAt;
        this.version = version;
    }

    /**
     * Accepts a parsed upload for processing and raises {@link BulkFileAccepted}.
     */
    public static BulkFile accept(String fileId,
                                  String consentId,
                                  String tppId,
                                  String idempotencyKey,
                                  String requestHash,
                                  String fileName,
                                  BulkIntegrityMode integrityMode,
                                  ParsedBulkFile parsed,
                                  Instant now) {
        Objects.requireNonNull(parsed, "parsed");
        BulkFile file = new BulkFile(fileId, consentId, tppId, idempotencyKey, requestHash, fileName, integrityMode,
                BulkFileStatus.PROCESSING, parsed.targetStatus(), 0, parsed.totalCount(), parsed.acceptedCount(),
                parsed.rejectedCount(), parsed.totalAmount(), parsed.acceptedAmount(), now, null, 0L);
        file.domainEvents.add(new BulkFileAccepted(UUID.randomUUID(), file.fileId, 0L, now, file.consentId,
                file.tppId, integrityMode, file.totalCount, file.acceptedCount, file.rejectedCount, file.totalAmount));
        return file;
    }

    /** Rebuilds a stored file; raises no events. */
    public static BulkFile rehydrate(String fileId,
                                     String consentId,
                                     String tppId,
                                     String idempotencyKey,
                                     String requestHash,
                                     String fileName,
                                     BulkIntegrityMode integrityMode,
                                     BulkFileStatus status,
                                     BulkFileStatus targetStatus,
                                     int processedCount,
                                     int totalCount,
                                     int acceptedCount,
                                     int rejectedCount,
                                     BigDecimal totalAmount,
                                     BigDecimal acceptedAmount,
                                     Instant createdAt,
                                     Instant processedAt,
                                     long version) {
        return new BulkFile(fileId, consentId, tppId, idempotencyKey, requestHash, fileName, integrityMode, status,
                targetStatus, processedCount, totalCount, acceptedCount, rejectedCount, totalAmount, acceptedAmount,
                createdAt, processedAt, version);
    }

    /**
     * Records that {@code itemCount} more items were processed. When the last
     * item is done the file moves to its target status and raises
     * {@link BulkFileCompleted} or {@link BulkFileRejected}.
     */
    public void recordProcessedBatch(int itemCount, Instant now) {
        Objects.requireNonNull(now, "now");
        if (status != BulkFileStatus.PROCESSING) {
            throw new BusinessRuleViolationException("Bulk file " + fileId + " is not processing");
        }
        if (itemCount <= 0) {
            throw new IllegalArgumentException("itemCount must be positive");
        }
        if (processedCount + itemCount > totalCount) {
            throw new BusinessRuleViolationException("Bulk file " + fileId + " has only "
                    + remainingItems() + " unprocessed items");
        }
        processedCount += itemCount;
        if (processedCount < totalCount) {
            return;
        }
        status = targetStatus;
        processedAt = now;
        long nextVersion = version + 1;
        if (status == BulkFileStatus.REJECTED) {
            domainEvents.add(new BulkFileRejected(UUID.randomUUID(), fileId, nextVersion, now, totalCount, rejectedCount));
        } else {
            domainEvents.add(new BulkFileCompleted(UUID.randomUUID(), fileId, nextVersion, now, status, totalCount,
                    acceptedCount, rejectedCount, acceptedAmount));
        }
    }

    /** Returns the events raised since the last call and forgets them. */
    public List<BulkFileEvent> pullDomainEvents() {
        List<BulkFileEvent> events = List.copyOf(domainEvents);
        domainEvents.clear();
        return events;
    }

    public boolean belongsToTpp(String candidateTppId) {
        return tppId.equals(candidateTppId);
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    public int remainingItems() {
        return totalCount - processedCount;
    }

    public String fileId() {
        return fileId;
    }

    public String consentId() {
        return consentId;
    }

    public String tppId() {
        return tppId;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String requestHash() {
        return requestHash;
    }

    public String fileName() {
        return fileName;
    }

    public BulkIntegrityMode integrityMode() {
        return integrityMode;
    }

    public BulkFileStatus status() {
        return status;
    }

    public BulkFileStatus targetStatus() {
        return targetStatus;
    }

    public int processedCount() {
        return processedCount;
    }

    public int totalCount() {
        return totalCount;
    }

    public int acceptedCount() {
        return acceptedCount;
    }

    public int rejectedCount() {
        return rejectedCount;
    }

    public BigDecimal totalAmount() {
        return totalAmount;
    }

    public BigDecimal acceptedAmount() {
        return acceptedAmount;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant processedAt() {
        return processedAt;
    }

    /** Version as loaded (0 for a file not yet stored); used for optimistic locking. */
    public long version() {
        return version;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
