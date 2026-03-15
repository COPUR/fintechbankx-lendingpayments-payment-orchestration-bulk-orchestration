package com.enterprise.openfinance.bulkpayments.domain.model;

import java.math.BigDecimal;

public record BulkItemResult(
        int lineNumber,
        String instructionId,
        String payeeIban,
        BigDecimal amount,
        BulkItemStatus status,
        String errorMessage
) {

    public BulkItemResult {
        if (lineNumber <= 0) {
            throw new IllegalArgumentException("lineNumber must be positive");
        }
        if (isBlank(instructionId)) {
            throw new IllegalArgumentException("instructionId is required");
        }
        if (isBlank(payeeIban)) {
            throw new IllegalArgumentException("payeeIban is required");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }

        instructionId = instructionId.trim();
        payeeIban = payeeIban.trim();
        errorMessage = normalize(errorMessage);
    }

    public static BulkItemResult accepted(int lineNumber,
                                          String instructionId,
                                          String payeeIban,
                                          BigDecimal amount) {
        return new BulkItemResult(lineNumber, instructionId, payeeIban, amount, BulkItemStatus.ACCEPTED, null);
    }

    public static BulkItemResult rejected(int lineNumber,
                                          String instructionId,
                                          String payeeIban,
                                          BigDecimal amount,
                                          String errorMessage) {
        return new BulkItemResult(lineNumber, instructionId, payeeIban, amount, BulkItemStatus.REJECTED, errorMessage);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
