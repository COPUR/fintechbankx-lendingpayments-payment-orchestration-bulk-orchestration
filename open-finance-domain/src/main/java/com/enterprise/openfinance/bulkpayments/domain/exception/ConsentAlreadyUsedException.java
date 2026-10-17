package com.enterprise.openfinance.bulkpayments.domain.exception;

/** A bulk payment consent authorises one file; this one is already bound to another file. */
public class ConsentAlreadyUsedException extends RuntimeException {

    public ConsentAlreadyUsedException(String message) {
        super(message);
    }
}
