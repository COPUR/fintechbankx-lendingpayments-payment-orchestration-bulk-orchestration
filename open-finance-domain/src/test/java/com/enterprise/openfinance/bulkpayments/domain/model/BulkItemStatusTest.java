package com.enterprise.openfinance.bulkpayments.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BulkItemStatusTest {

    @Test
    void shouldExposeApiValues() {
        assertThat(BulkItemStatus.ACCEPTED.apiValue()).isEqualTo("Accepted");
        assertThat(BulkItemStatus.REJECTED.apiValue()).isEqualTo("Rejected");
    }
}
