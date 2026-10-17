package com.enterprise.openfinance.bulkpayments.domain.exception;

/**
 * The accounts ledger refused a journal entry. Carries the ledger's code and the
 * entry reference only; never the ledger's message, which may describe balances.
 */
public class JournalEntryRejectedException extends RuntimeException {

    public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";
    public static final String CONCURRENT_UPDATE = "CONCURRENT_UPDATE";
    public static final String DUPLICATE_REQUEST = "DUPLICATE_REQUEST";
    public static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
    public static final String ACCOUNT_NOT_OPERATIONAL = "ACCOUNT_NOT_OPERATIONAL";
    public static final String UNBALANCED_ENTRY = "UNBALANCED_ENTRY";

    private final String reference;
    private final String code;

    public JournalEntryRejectedException(String reference, String code) {
        super("Accounts ledger rejected journal entry " + reference + ": " + code);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code is required");
        }
        this.reference = reference;
        this.code = code;
    }

    public String reference() {
        return reference;
    }

    public String code() {
        return code;
    }

    /** The ledger asks for a retry with the same reference. */
    public boolean isRetryable() {
        return CONCURRENT_UPDATE.equals(code) || DUPLICATE_REQUEST.equals(code);
    }
}
