package com.enterprise.openfinance.bulkpayments.infrastructure.consent;

/** The consent service could not answer; uploads fail closed (HTTP 503). */
public class ConsentServiceUnavailableException extends RuntimeException {

    public ConsentServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
