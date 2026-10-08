package com.enterprise.openfinance.bulkpayments.domain.exception;

public class ForbiddenException extends RuntimeException {

    /**
     * The one message for every consent the caller may not use (unknown, another
     * TPP's, not authorised, revoked, expired, wrong scope): the 403 body never
     * says which, so consent ids cannot be probed.
     */
    public static final String CONSENT_NOT_USABLE = "Consent not usable for this request";

    public ForbiddenException(String message) {
        super(message);
    }
}
