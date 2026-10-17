package com.enterprise.openfinance.bulkpayments.domain.model;

import java.time.Instant;

/** The ledger's answer to a posting: its entry id and whether this was a replay of an earlier booking. */
public record JournalEntryReceipt(String journalEntryId, String reference, Instant bookedAt, boolean replay) {

    public JournalEntryReceipt {
        if (journalEntryId == null || journalEntryId.isBlank()) {
            throw new IllegalArgumentException("journalEntryId is required");
        }
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("reference is required");
        }
        if (bookedAt == null) {
            throw new IllegalArgumentException("bookedAt is required");
        }
    }
}
