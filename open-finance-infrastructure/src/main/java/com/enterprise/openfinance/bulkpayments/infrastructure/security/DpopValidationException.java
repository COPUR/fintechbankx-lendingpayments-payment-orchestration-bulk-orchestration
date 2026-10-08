package com.enterprise.openfinance.bulkpayments.infrastructure.security;

/** A DPoP proof or its binding to the access token is invalid (answered with 401). */
public class DpopValidationException extends RuntimeException {

    public DpopValidationException(String message) {
        super(message);
    }

    public DpopValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
