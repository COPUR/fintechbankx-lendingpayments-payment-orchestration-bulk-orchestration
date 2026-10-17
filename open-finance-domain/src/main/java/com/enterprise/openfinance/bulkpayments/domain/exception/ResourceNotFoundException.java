package com.enterprise.openfinance.bulkpayments.domain.exception;

public class ResourceNotFoundException extends RuntimeException {

    /**
     * The one 404 message for a bulk file id the caller cannot see: unknown, or another
     * TPP's (ADR-025 item 5), so a TPP cannot probe for other TPPs' file ids.
     */
    public static final String BULK_FILE_NOT_FOUND = "Bulk file not found";

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
