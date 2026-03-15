package com.enterprise.openfinance.bulkpayments.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BulkFileStatusTest {

    @Test
    void shouldExposeApiValuesAndTerminalFlag() {
        assertThat(BulkFileStatus.PROCESSING.apiValue()).isEqualTo("Processing");
        assertThat(BulkFileStatus.COMPLETED.apiValue()).isEqualTo("Completed");
        assertThat(BulkFileStatus.PARTIALLY_ACCEPTED.apiValue()).isEqualTo("PartiallyAccepted");
        assertThat(BulkFileStatus.REJECTED.apiValue()).isEqualTo("Rejected");

        assertThat(BulkFileStatus.PROCESSING.isTerminal()).isFalse();
        assertThat(BulkFileStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(BulkFileStatus.PARTIALLY_ACCEPTED.isTerminal()).isTrue();
        assertThat(BulkFileStatus.REJECTED.isTerminal()).isTrue();
    }
}
