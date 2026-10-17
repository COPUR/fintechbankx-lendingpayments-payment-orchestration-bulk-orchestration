package com.enterprise.openfinance.bulkpayments.infrastructure.ledger;

import com.enterprise.openfinance.bulkpayments.domain.exception.JournalEntryRejectedException;
import com.enterprise.openfinance.bulkpayments.domain.model.JournalEntry;
import com.enterprise.openfinance.bulkpayments.domain.model.JournalEntryReceipt;
import com.enterprise.openfinance.bulkpayments.domain.model.JournalLine;
import com.enterprise.openfinance.bulkpayments.domain.model.JournalSide;
import com.enterprise.openfinance.bulkpayments.domain.model.Money;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class InMemoryJournalEntryAdapterTest {

    private static final Currency AED = Currency.getInstance("AED");
    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    private final InMemoryJournalEntryAdapter adapter = new InMemoryJournalEntryAdapter(Clock.fixed(NOW, ZoneOffset.UTC));

    private static JournalEntry entry(String reference, String amount, String narrative) {
        return new JournalEntry(reference, AED, narrative, List.of(
                new JournalLine("ACC-DEBTOR", JournalSide.DEBIT, Money.of(amount, "AED")),
                new JournalLine("ACC-CLEARING", JournalSide.CREDIT, Money.of(amount, "AED"))));
    }

    @Test
    void booksAnEntryOncePerReference() {
        JournalEntryReceipt first = adapter.post(entry("REF-1", "40.00", "first"));

        assertThat(first.journalEntryId()).isNotBlank();
        assertThat(first.reference()).isEqualTo("REF-1");
        assertThat(first.bookedAt()).isEqualTo(NOW);
        assertThat(first.replay()).isFalse();
        assertThat(adapter.findByReference("REF-1")).contains(first);
        assertThat(adapter.findByReference("REF-2")).isEmpty();
    }

    @Test
    void theSameLinesUnderTheSameReferenceReplayTheFirstBooking() {
        JournalEntryReceipt first = adapter.post(entry("REF-1", "40.00", "first"));
        JournalEntryReceipt again = adapter.post(entry("REF-1", "40.00", "another narrative"));

        assertThat(again.journalEntryId()).isEqualTo(first.journalEntryId());
        assertThat(again.bookedAt()).isEqualTo(first.bookedAt());
        assertThat(again.replay()).isTrue();
    }

    @Test
    void otherLinesUnderTheSameReferenceAreRefused() {
        adapter.post(entry("REF-1", "40.00", null));

        assertThatThrownBy(() -> adapter.post(entry("REF-1", "41.00", null)))
                .isInstanceOfSatisfying(JournalEntryRejectedException.class,
                        rejected -> assertThat(rejected.code()).isEqualTo(JournalEntryRejectedException.IDEMPOTENCY_KEY_REUSED));
        assertThat(adapter.findByReference("REF-1")).isPresent();
    }

    @Test
    void differentReferencesGetDifferentEntries() {
        JournalEntryReceipt one = adapter.post(entry("REF-1", "40.00", null));
        JournalEntryReceipt two = adapter.post(entry("REF-2", "40.00", null));

        assertThat(two.journalEntryId()).isNotEqualTo(one.journalEntryId());
        assertThat(new InMemoryJournalEntryAdapter().post(entry("REF-3", "1.00", null)).bookedAt()).isNotNull();
    }
}
