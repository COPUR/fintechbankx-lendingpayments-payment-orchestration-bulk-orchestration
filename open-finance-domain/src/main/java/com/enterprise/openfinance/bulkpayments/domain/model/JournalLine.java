package com.enterprise.openfinance.bulkpayments.domain.model;

/** One posting line of a {@link JournalEntry}: a positive amount on one side of one account. */
public record JournalLine(String accountId, JournalSide side, Money amount) {

    public JournalLine {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId is required");
        }
        if (side == null) {
            throw new IllegalArgumentException("side is required");
        }
        if (amount == null) {
            throw new IllegalArgumentException("amount is required");
        }
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        accountId = accountId.trim();
    }
}
