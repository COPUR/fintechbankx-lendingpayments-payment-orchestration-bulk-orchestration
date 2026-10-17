package com.enterprise.openfinance.bulkpayments.infrastructure.ledger;

import com.enterprise.openfinance.bulkpayments.domain.exception.JournalEntryRejectedException;
import com.enterprise.openfinance.bulkpayments.domain.model.JournalEntry;
import com.enterprise.openfinance.bulkpayments.domain.model.JournalEntryReceipt;
import com.enterprise.openfinance.bulkpayments.domain.model.JournalLine;
import com.enterprise.openfinance.bulkpayments.domain.port.out.JournalEntryPort;

import java.time.Clock;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bookings kept in memory for local runs and tests only ({@code journal.adapter=in-memory}).
 * Follows the ledger's idempotency rule: the same reference with the same lines replays
 * the first booking, with other lines it is IDEMPOTENCY_KEY_REUSED. Deployed
 * environments post to the accounts ledger.
 */
public class InMemoryJournalEntryAdapter implements JournalEntryPort {

    private record Booking(Currency currency, List<JournalLine> lines, JournalEntryReceipt receipt) {
    }

    private final Map<String, Booking> bookings = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryJournalEntryAdapter() {
        this(Clock.systemUTC());
    }

    public InMemoryJournalEntryAdapter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public JournalEntryReceipt post(JournalEntry entry) {
        AtomicBoolean booked = new AtomicBoolean(false);
        Booking booking = bookings.compute(entry.reference(), (reference, existing) -> {
            if (existing == null) {
                booked.set(true);
                return new Booking(entry.currency(), entry.lines(),
                        new JournalEntryReceipt("JE-" + UUID.randomUUID(), reference, clock.instant(), false));
            }
            if (!existing.currency().equals(entry.currency()) || !existing.lines().equals(entry.lines())) {
                throw new JournalEntryRejectedException(reference, JournalEntryRejectedException.IDEMPOTENCY_KEY_REUSED);
            }
            return existing;
        });
        JournalEntryReceipt first = booking.receipt();
        return booked.get() ? first
                : new JournalEntryReceipt(first.journalEntryId(), first.reference(), first.bookedAt(), true);
    }

    public Optional<JournalEntryReceipt> findByReference(String reference) {
        return Optional.ofNullable(bookings.get(reference)).map(Booking::receipt);
    }
}
