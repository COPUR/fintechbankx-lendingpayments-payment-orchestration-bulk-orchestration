package com.enterprise.openfinance.bulkpayments.domain.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GetBulkFileStatusQueryTest {

    @Test
    void shouldCreateAndNormalizeStatusQuery() {
        GetBulkFileStatusQuery query = new GetBulkFileStatusQuery(" FILE-001 ", " TPP-001 ", " ix-1 ");

        assertThat(query.fileId()).isEqualTo("FILE-001");
        assertThat(query.tppId()).isEqualTo("TPP-001");
        assertThat(query.interactionId()).isEqualTo("ix-1");
    }

    @Test
    void shouldRejectInvalidStatusQuery() {
        assertInvalid("", "TPP-001", "ix-1", "fileId");
        assertInvalid("FILE-001", "", "ix-1", "tppId");
        assertInvalid("FILE-001", "TPP-001", "", "interactionId");
    }

    private static void assertInvalid(String fileId,
                                      String tppId,
                                      String interactionId,
                                      String expectedField) {
        assertThatThrownBy(() -> new GetBulkFileStatusQuery(fileId, tppId, interactionId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedField);
    }
}
