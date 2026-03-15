package com.enterprise.openfinance.bulkpayments.domain.query;

public record GetBulkFileStatusQuery(
        String fileId,
        String tppId,
        String interactionId
) {

    public GetBulkFileStatusQuery {
        if (isBlank(fileId)) {
            throw new IllegalArgumentException("fileId is required");
        }
        if (isBlank(tppId)) {
            throw new IllegalArgumentException("tppId is required");
        }
        if (isBlank(interactionId)) {
            throw new IllegalArgumentException("interactionId is required");
        }

        fileId = fileId.trim();
        tppId = tppId.trim();
        interactionId = interactionId.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
