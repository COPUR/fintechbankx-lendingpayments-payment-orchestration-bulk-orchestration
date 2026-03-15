package com.enterprise.openfinance.bulkpayments.infrastructure.rest.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

public record BulkErrorResponse(
        String code,
        String message,
        String interactionId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant timestamp
) {

    public static BulkErrorResponse of(String code, String message, String interactionId) {
        return new BulkErrorResponse(code, message, interactionId, Instant.now());
    }
}
