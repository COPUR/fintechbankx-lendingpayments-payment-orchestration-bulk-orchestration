package com.enterprise.openfinance.bulkpayments.domain.exception;

public class ForbiddenException extends RuntimeException {

    /**
     * The one message for every consent the caller may not use (unknown, another
     * TPP's, not authorised, revoked, expired, wrong scope), on a new upload and
     * on the retry of an accepted upload alike (ADR-025 item 5): the 403 body
     * never says which, so consent ids cannot be probed.
     */
    public static final String CONSENT_NOT_USABLE = "Consent not usable for this request";

    public ForbiddenException(String message) {
        super(message);
    }
}
