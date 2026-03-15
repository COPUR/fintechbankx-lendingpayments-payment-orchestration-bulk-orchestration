package com.enterprise.openfinance.bulkpayments.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BulkIntegrityModeTest {

    @Test
    void shouldParseModeFromApiValue() {
        assertThat(BulkIntegrityMode.fromApiValue("PARTIAL_REJECTION")).isEqualTo(BulkIntegrityMode.PARTIAL_REJECTION);
        assertThat(BulkIntegrityMode.fromApiValue("full_rejection")).isEqualTo(BulkIntegrityMode.FULL_REJECTION);
        assertThat(BulkIntegrityMode.fromApiValue(null)).isEqualTo(BulkIntegrityMode.PARTIAL_REJECTION);
    }
}
