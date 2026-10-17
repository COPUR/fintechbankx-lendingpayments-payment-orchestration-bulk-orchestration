package com.enterprise.openfinance.bulkpayments.domain.exception;

/** The accounts ledger could not answer, or answered something other than a booking or a coded rejection. */
public class AccountsLedgerUnavailableException extends RuntimeException {

    public AccountsLedgerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
