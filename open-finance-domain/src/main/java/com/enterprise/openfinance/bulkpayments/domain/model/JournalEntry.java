package com.enterprise.openfinance.bulkpayments.domain.model;

import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * A double entry to post to the accounts ledger (svc-acc-accounts-ledger) through
 * {@code JournalEntryPort}. The reference is this service's business key for the
 * booking: the ledger books one entry per reference and replays it on a retry.
 * Lines share the entry currency and debits equal credits; the ledger checks the
 * same and more (funds, account state), so this is the caller's early guard only.
 */
public record JournalEntry(String reference, Currency currency, String narrative, List<JournalLine> lines) {

    public JournalEntry {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("reference is required");
        }
        if (currency == null) {
            throw new IllegalArgumentException("currency is required");
        }
        if (lines == null || lines.size() < 2 || lines.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("A journal entry needs at least two lines");
        }
        reference = reference.trim();
        narrative = narrative == null || narrative.isBlank() ? null : narrative.trim();
        lines = List.copyOf(lines);
        for (JournalLine line : lines) {
            if (!currency.equals(line.amount().currency())) {
                throw new IllegalArgumentException("A line is in " + line.amount().currency().getCurrencyCode()
                        + ", the entry is in " + currency.getCurrencyCode());
            }
        }
        Money debits = total(lines, JournalSide.DEBIT, currency);
        Money credits = total(lines, JournalSide.CREDIT, currency);
        if (!debits.equals(credits)) {
            throw new IllegalArgumentException("Unbalanced entry: debits " + debits.toPlainString()
                    + ", credits " + credits.toPlainString());
        }
    }

    public Money total(JournalSide side) {
        return total(lines, side, currency);
    }

    private static Money total(List<JournalLine> lines, JournalSide side, Currency currency) {
        Money sum = Money.zero(currency);
        for (JournalLine line : lines) {
            if (line.side() == side) {
                sum = sum.add(line.amount());
            }
        }
        return sum;
    }
}
