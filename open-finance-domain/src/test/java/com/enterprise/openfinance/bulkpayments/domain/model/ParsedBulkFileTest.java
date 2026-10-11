package com.enterprise.openfinance.bulkpayments.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParsedBulkFileTest {

    @Test
    void itemsAndTotalsMustShareOneCurrency() {
        List<BulkItemResult> items = List.of(
                BulkItemResult.accepted(1, "INS-1", "AE120001000000000000000001", Money.of("10", "AED")),
                BulkItemResult.accepted(2, "INS-2", "AE120001000000000000000001", Money.of("10", "JPY")));

        assertThatThrownBy(() -> new ParsedBulkFile(items, 2, 2, 0, Money.of("20", "AED"), Money.of("20", "AED"),
                BulkFileStatus.VALIDATED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");
    }
}
