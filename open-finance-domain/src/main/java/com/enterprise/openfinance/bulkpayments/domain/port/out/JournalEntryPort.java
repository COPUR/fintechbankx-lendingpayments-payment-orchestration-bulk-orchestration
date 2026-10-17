package com.enterprise.openfinance.bulkpayments.domain.port.out;

import com.enterprise.openfinance.bulkpayments.domain.model.JournalEntry;
import com.enterprise.openfinance.bulkpayments.domain.model.JournalEntryReceipt;

/**
 * Posts journal entries to the accounts ledger (svc-acc-accounts-ledger, ADR-030).
 * Fails closed: a rejected entry raises {@code JournalEntryRejectedException}
 * with the ledger's code, anything else {@code AccountsLedgerUnavailableException}.
 */
public interface JournalEntryPort {

    JournalEntryReceipt post(JournalEntry entry);
}
