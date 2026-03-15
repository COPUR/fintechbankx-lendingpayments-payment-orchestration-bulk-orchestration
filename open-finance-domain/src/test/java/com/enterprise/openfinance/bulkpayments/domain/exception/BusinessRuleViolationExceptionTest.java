package com.enterprise.openfinance.bulkpayments.domain.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessRuleViolationExceptionTest {

    @Test
    void shouldPreserveMessage() {
        BusinessRuleViolationException exception = new BusinessRuleViolationException("rule");

        assertThat(exception.getMessage()).isEqualTo("rule");
    }
}
