package com.enterprise.openfinance.bulkpayments.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BulkFileStatusTest {

    @Test
    void apiValues() {
        assertThat(BulkFileStatus.PROCESSING.apiValue()).isEqualTo("Processing");
        assertThat(BulkFileStatus.VALIDATED.apiValue()).isEqualTo("Validated");
        assertThat(BulkFileStatus.REJECTED.apiValue()).isEqualTo("Rejected");
    }

    /** Until items reach initiation-settlement, a validated file is not complete and not final. */
    @Test
    void onlyRejectedAndStoppedAreFinalAndNothingClaimsCompletion() {
        assertThat(BulkFileStatus.values()).extracting(Enum::name)
                .containsExactly("PROCESSING", "VALIDATED", "REJECTED", "STOPPED");
        assertThat(BulkFileStatus.STOPPED.isValidationFinished()).isFalse();
        assertThat(BulkFileStatus.STOPPED.isTerminal()).isTrue();
        assertThat(BulkFileStatus.PROCESSING.isValidationFinished()).isFalse();
        assertThat(BulkFileStatus.VALIDATED.isValidationFinished()).isTrue();
        assertThat(BulkFileStatus.REJECTED.isValidationFinished()).isTrue();
        assertThat(BulkFileStatus.PROCESSING.isTerminal()).isFalse();
        assertThat(BulkFileStatus.VALIDATED.isTerminal()).isFalse();
        assertThat(BulkFileStatus.REJECTED.isTerminal()).isTrue();
    }
}
