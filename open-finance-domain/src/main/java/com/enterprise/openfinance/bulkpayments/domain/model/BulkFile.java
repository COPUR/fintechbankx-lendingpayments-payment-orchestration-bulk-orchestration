package com.enterprise.openfinance.bulkpayments.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

public record BulkFile(
        String fileId,
        String consentId,
        String tppId,
        String idempotencyKey,
        String requestHash,
        String fileName,
        BulkIntegrityMode integrityMode,
        BulkFileStatus status,
        BulkFileStatus targetStatus,
        int pollCount,
        int totalCount,
        int acceptedCount,
        int rejectedCount,
        BigDecimal totalAmount,
        Instant createdAt,
        Instant processedAt
) {

    public BulkFile {
        if (isBlank(fileId)) {
            throw new IllegalArgumentException("fileId is required");
        }
        if (isBlank(consentId)) {
            throw new IllegalArgumentException("consentId is required");
        }
        if (isBlank(tppId)) {
            throw new IllegalArgumentException("tppId is required");
        }
        if (isBlank(idempotencyKey)) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        if (isBlank(requestHash)) {
            throw new IllegalArgumentException("requestHash is required");
        }
        if (isBlank(fileName)) {
            throw new IllegalArgumentException("fileName is required");
        }
        if (integrityMode == null) {
            throw new IllegalArgumentException("integrityMode is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (targetStatus == null) {
            throw new IllegalArgumentException("targetStatus is required");
        }
        if (pollCount < 0) {
            throw new IllegalArgumentException("pollCount must be >= 0");
        }
        if (totalCount <= 0) {
            throw new IllegalArgumentException("totalCount must be > 0");
        }
        if (acceptedCount < 0 || acceptedCount > totalCount) {
            throw new IllegalArgumentException("acceptedCount out of range");
        }
        if (rejectedCount < 0 || rejectedCount > totalCount) {
            throw new IllegalArgumentException("rejectedCount out of range");
        }
        if (acceptedCount + rejectedCount > totalCount) {
            throw new IllegalArgumentException("acceptedCount + rejectedCount must be <= totalCount");
        }
        if (totalAmount == null || totalAmount.signum() <= 0) {
            throw new IllegalArgumentException("totalAmount must be positive");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt is required");
        }

        fileId = fileId.trim();
        consentId = consentId.trim();
        tppId = tppId.trim();
        idempotencyKey = idempotencyKey.trim();
        requestHash = requestHash.trim();
        fileName = fileName.trim();
    }

    public boolean belongsToTpp(String candidateTppId) {
        return tppId.equals(candidateTppId);
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    public BulkFile advanceProcessing(int statusPollsToComplete, Instant now) {
        if (status != BulkFileStatus.PROCESSING) {
            return this;
        }

        int nextPoll = pollCount + 1;
        if (nextPoll >= statusPollsToComplete) {
            return new BulkFile(
                    fileId,
                    consentId,
                    tppId,
                    idempotencyKey,
                    requestHash,
                    fileName,
                    integrityMode,
                    targetStatus,
                    targetStatus,
                    nextPoll,
                    totalCount,
                    acceptedCount,
                    rejectedCount,
                    totalAmount,
                    createdAt,
                    now
            );
        }

        return new BulkFile(
                fileId,
                consentId,
                tppId,
                idempotencyKey,
                requestHash,
                fileName,
                integrityMode,
                status,
                targetStatus,
                nextPoll,
                totalCount,
                acceptedCount,
                rejectedCount,
                totalAmount,
                createdAt,
                processedAt
        );
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
