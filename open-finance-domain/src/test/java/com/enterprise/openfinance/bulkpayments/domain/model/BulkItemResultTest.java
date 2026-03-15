package com.enterprise.openfinance.bulkpayments.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BulkItemResultTest {

    @Test
    void shouldCreateAcceptedAndRejectedItems() {
        BulkItemResult accepted = BulkItemResult.accepted(1, "INS-1", "AE120001000000000000000001", new BigDecimal("10.00"));
        BulkItemResult rejected = BulkItemResult.rejected(2, "INS-2", "AE000", new BigDecimal("11.00"), "Invalid IBAN");

        assertThat(accepted.status()).isEqualTo(BulkItemStatus.ACCEPTED);
        assertThat(accepted.errorMessage()).isNull();
        assertThat(rejected.status()).isEqualTo(BulkItemStatus.REJECTED);
        assertThat(rejected.errorMessage()).contains("Invalid");
    }

    @Test
    void shouldRejectInvalidItemConstruction() {
        assertInvalid(0, "INS-1", "AE120001000000000000000001", new BigDecimal("10.00"), BulkItemStatus.ACCEPTED, null, "lineNumber");
        assertInvalid(1, "", "AE120001000000000000000001", new BigDecimal("10.00"), BulkItemStatus.ACCEPTED, null, "instructionId");
        assertInvalid(1, "INS-1", "", new BigDecimal("10.00"), BulkItemStatus.ACCEPTED, null, "payeeIban");
        assertInvalid(1, "INS-1", "AE120001000000000000000001", null, BulkItemStatus.ACCEPTED, null, "amount");
        assertInvalid(1, "INS-1", "AE120001000000000000000001", new BigDecimal("0.00"), BulkItemStatus.ACCEPTED, null, "amount");
        assertInvalid(1, "INS-1", "AE120001000000000000000001", new BigDecimal("10.00"), null, null, "status");
    }

    private static void assertInvalid(int lineNumber,
                                      String instructionId,
                                      String payeeIban,
                                      BigDecimal amount,
                                      BulkItemStatus status,
                                      String errorMessage,
                                      String expectedField) {
        assertThatThrownBy(() -> new BulkItemResult(
                lineNumber,
                instructionId,
                payeeIban,
                amount,
                status,
                errorMessage
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedField);
    }
}
