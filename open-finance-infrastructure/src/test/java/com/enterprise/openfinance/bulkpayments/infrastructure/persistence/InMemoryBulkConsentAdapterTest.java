package com.enterprise.openfinance.bulkpayments.infrastructure.persistence;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class InMemoryBulkConsentAdapterTest {

    @Test
    void shouldReturnSeededConsent() {
        InMemoryBulkConsentAdapter adapter = new InMemoryBulkConsentAdapter();

        assertThat(adapter.findById("CONS-BULK-001")).isPresent();
        assertThat(adapter.findById("CONS-UNKNOWN")).isEmpty();
    }
}
