package com.enterprise.openfinance.bulkpayments.domain.model;

public enum BulkIntegrityMode {
    PARTIAL_REJECTION,
    FULL_REJECTION;

    public static BulkIntegrityMode fromApiValue(String value) {
        if (value == null || value.isBlank()) {
            return PARTIAL_REJECTION;
        }

        String normalized = value.trim().toUpperCase().replace('-', '_');
        return switch (normalized) {
            case "PARTIAL_REJECTION", "PARTIALREJECTION" -> PARTIAL_REJECTION;
            case "FULL_REJECTION", "FULLREJECTION" -> FULL_REJECTION;
            default -> PARTIAL_REJECTION;
        };
    }
}
