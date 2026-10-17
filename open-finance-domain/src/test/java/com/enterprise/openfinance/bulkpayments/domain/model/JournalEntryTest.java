package com.enterprise.openfinance.bulkpayments.domain.model;

import org.junit.jupiter.api.Test;

import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalEntryTest {

    private static final Currency AED = Currency.getInstance("AED");

    private static JournalLine debit(String accountId, String amount) {
        return new JournalLine(accountId, JournalSide.DEBIT, Money.of(amount, "AED"));
    }

    private static JournalLine credit(String accountId, String amount) {
        return new JournalLine(accountId, JournalSide.CREDIT, Money.of(amount, "AED"));
    }

    @Test
    void aBalancedEntryKeepsItsLinesAndTotals() {
        JournalEntry entry = new JournalEntry("BULK-FILE-1/ITEM-7", AED, "bulk item 7",
                List.of(debit("ACC-DEBTOR", "40.00"), credit("ACC-CLEARING", "25.00"), credit("ACC-FEES", "15.00")));

        assertThat(entry.reference()).isEqualTo("BULK-FILE-1/ITEM-7");
        assertThat(entry.currency()).isEqualTo(AED);
        assertThat(entry.narrative()).isEqualTo("bulk item 7");
        assertThat(entry.lines()).hasSize(3);
        assertThat(entry.total(JournalSide.DEBIT)).isEqualTo(Money.of("40.00", "AED"));
        assertThat(entry.total(JournalSide.CREDIT)).isEqualTo(Money.of("40.00", "AED"));
    }

    @Test
    void narrativeIsOptionalAndLinesAreImmutable() {
        JournalEntry entry = new JournalEntry(" REF-1 ", AED, null,
                List.of(debit("ACC-1", "10.00"), credit("ACC-2", "10.00")));

        assertThat(entry.reference()).isEqualTo("REF-1");
        assertThat(entry.narrative()).isNull();
        assertThatThrownBy(() -> entry.lines().add(debit("ACC-3", "1.00")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void refusesAnUnbalancedEntry() {
        assertThatThrownBy(() -> new JournalEntry("REF-1", AED, null,
                List.of(debit("ACC-1", "40.00"), credit("ACC-2", "39.99"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("debits 40.00")
                .hasMessageContaining("credits 39.99");
    }

    @Test
    void refusesALineInAnotherCurrency() {
        JournalLine usd = new JournalLine("ACC-2", JournalSide.CREDIT, Money.of("40.00", "USD"));

        assertThatThrownBy(() -> new JournalEntry("REF-1", AED, null, List.of(debit("ACC-1", "40.00"), usd)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("USD");
    }

    @Test
    void needsAtLeastTwoLinesAReferenceAndACurrency() {
        assertThatThrownBy(() -> new JournalEntry("REF-1", AED, null, List.of(debit("ACC-1", "40.00"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("two lines");
        assertThatThrownBy(() -> new JournalEntry("REF-1", AED, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JournalEntry(" ", AED, null,
                List.of(debit("ACC-1", "1.00"), credit("ACC-2", "1.00"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reference");
        assertThatThrownBy(() -> new JournalEntry("REF-1", null, null,
                List.of(debit("ACC-1", "1.00"), credit("ACC-2", "1.00"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");
    }

    @Test
    void aLineNeedsAnAccountASideAndAPositiveAmount() {
        assertThatThrownBy(() -> new JournalLine(" ", JournalSide.DEBIT, Money.of("1.00", "AED")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountId");
        assertThatThrownBy(() -> new JournalLine("ACC-1", null, Money.of("1.00", "AED")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("side");
        assertThatThrownBy(() -> new JournalLine("ACC-1", JournalSide.DEBIT, Money.of("0.00", "AED")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> new JournalLine("ACC-1", JournalSide.DEBIT, Money.of("-1.00", "AED")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> new JournalLine("ACC-1", JournalSide.DEBIT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void receiptCarriesTheLedgerIdAndTheReplayFlag() {
        JournalEntryReceipt receipt = new JournalEntryReceipt("JE-1", "REF-1",
                java.time.Instant.parse("2026-10-17T10:00:00Z"), true);

        assertThat(receipt.journalEntryId()).isEqualTo("JE-1");
        assertThat(receipt.replay()).isTrue();
        assertThatThrownBy(() -> new JournalEntryReceipt(null, "REF-1", java.time.Instant.EPOCH, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JournalEntryReceipt("JE-1", "REF-1", null, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
