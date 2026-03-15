package com.enterprise.openfinance.bulkpayments.domain.model;

import java.time.Instant;
import java.util.List;

public record BulkFileReport(
        String fileId,
        BulkFileStatus status,
        int totalCount,
        int acceptedCount,
        int rejectedCount,
        List<BulkItemResult> items,
        Instant generatedAt
) {

    public BulkFileReport {
        if (isBlank(fileId)) {
            throw new IllegalArgumentException("fileId is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
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
        if (items == null) {
            throw new IllegalArgumentException("items are required");
        }
        if (generatedAt == null) {
            throw new IllegalArgumentException("generatedAt is required");
        }

        fileId = fileId.trim();
        items = List.copyOf(items);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
