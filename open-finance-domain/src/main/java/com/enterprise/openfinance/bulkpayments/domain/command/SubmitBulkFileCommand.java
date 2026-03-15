package com.enterprise.openfinance.bulkpayments.domain.command;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkIntegrityMode;

public record SubmitBulkFileCommand(
        String tppId,
        String consentId,
        String idempotencyKey,
        String fileName,
        String fileContent,
        String fileHash,
        BulkIntegrityMode integrityMode,
        String interactionId
) {

    public SubmitBulkFileCommand {
        if (isBlank(tppId)) {
            throw new IllegalArgumentException("tppId is required");
        }
        if (isBlank(consentId)) {
            throw new IllegalArgumentException("consentId is required");
        }
        if (isBlank(idempotencyKey)) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        if (isBlank(fileName)) {
            throw new IllegalArgumentException("fileName is required");
        }
        if (fileContent == null) {
            throw new IllegalArgumentException("fileContent is required");
        }
        if (isBlank(fileHash)) {
            throw new IllegalArgumentException("fileHash is required");
        }
        if (integrityMode == null) {
            throw new IllegalArgumentException("integrityMode is required");
        }
        if (isBlank(interactionId)) {
            throw new IllegalArgumentException("interactionId is required");
        }

        tppId = tppId.trim();
        consentId = consentId.trim();
        idempotencyKey = idempotencyKey.trim();
        fileName = fileName.trim();
        fileContent = fileContent.trim();
        fileHash = fileHash.trim();
        interactionId = interactionId.trim();
    }

    public String requestHash() {
        return consentId + '|' + fileName + '|' + fileHash + '|' + integrityMode.name();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
