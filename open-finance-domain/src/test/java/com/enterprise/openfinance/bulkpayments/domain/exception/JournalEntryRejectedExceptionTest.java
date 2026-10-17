package com.enterprise.openfinance.bulkpayments.domain.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalEntryRejectedExceptionTest {

    @Test
    void carriesTheLedgerCodeAndNothingElse() {
        JournalEntryRejectedException exception =
                new JournalEntryRejectedException("REF-1", JournalEntryRejectedException.INSUFFICIENT_FUNDS);

        assertThat(exception.reference()).isEqualTo("REF-1");
        assertThat(exception.code()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(exception.getMessage()).isEqualTo("Accounts ledger rejected journal entry REF-1: INSUFFICIENT_FUNDS");
        assertThat(exception.getCause()).isNull();
    }

    @Test
    void onlyConcurrentUpdateAndDuplicateRequestAreRetryable() {
        assertThat(new JournalEntryRejectedException("R", JournalEntryRejectedException.CONCURRENT_UPDATE).isRetryable()).isTrue();
        assertThat(new JournalEntryRejectedException("R", JournalEntryRejectedException.DUPLICATE_REQUEST).isRetryable()).isTrue();
        assertThat(new JournalEntryRejectedException("R", JournalEntryRejectedException.IDEMPOTENCY_KEY_REUSED).isRetryable()).isFalse();
        assertThat(new JournalEntryRejectedException("R", JournalEntryRejectedException.INSUFFICIENT_FUNDS).isRetryable()).isFalse();
        assertThat(new JournalEntryRejectedException("R", JournalEntryRejectedException.ACCOUNT_NOT_OPERATIONAL).isRetryable()).isFalse();
        assertThat(new JournalEntryRejectedException("R", JournalEntryRejectedException.UNBALANCED_ENTRY).isRetryable()).isFalse();
        assertThat(new JournalEntryRejectedException("R", "SOMETHING_NEW").isRetryable()).isFalse();
    }

    @Test
    void needsACode() {
        assertThatThrownBy(() -> new JournalEntryRejectedException("REF-1", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unavailableLedgerIsASeparateFailure() {
        AccountsLedgerUnavailableException exception =
                new AccountsLedgerUnavailableException("Accounts ledger unavailable", new IllegalStateException("x"));

        assertThat(exception).isNotInstanceOf(JournalEntryRejectedException.class);
        assertThat(exception.getMessage()).isEqualTo("Accounts ledger unavailable");
        assertThat(exception.getCause()).isInstanceOf(IllegalStateException.class);
    }
}
