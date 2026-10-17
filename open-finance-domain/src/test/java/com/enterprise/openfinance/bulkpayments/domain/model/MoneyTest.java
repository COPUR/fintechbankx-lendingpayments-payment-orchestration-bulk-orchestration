package com.enterprise.openfinance.bulkpayments.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    private static final Currency AED = Currency.getInstance("AED");
    private static final Currency JPY = Currency.getInstance("JPY");
    private static final Currency KWD = Currency.getInstance("KWD");

    @Test
    void keepsTheAmountAtTheCurrencyMinorUnitsWithoutRounding() {
        assertThat(Money.of("10", "AED").amount()).isEqualByComparingTo("10.00").hasScaleOf(2);
        assertThat(Money.of("1.234", "KWD").amount()).hasToString("1.234");
        assertThat(Money.of("1500", "JPY").amount()).hasToString("1500");
        assertThat(Money.of("1500.0", "JPY").amount()).hasToString("1500");
        assertThat(new Money(new BigDecimal("1E+3"), AED).toPlainString()).isEqualTo("1000.00");
    }

    @Test
    void rejectsMoreDecimalsThanTheCurrencyAllows() {
        assertThatThrownBy(() -> Money.of("10.001", "AED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AED allows 2");
        assertThatThrownBy(() -> Money.of("10.5", "JPY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JPY allows 0");
        assertThatThrownBy(() -> Money.of("1.2345", "KWD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KWD allows 3");
    }

    @Test
    void rejectsCurrenciesWithoutMinorUnitsAndMissingValues() {
        assertThatThrownBy(() -> Money.of("1", "XAU")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("XAU");
        assertThatThrownBy(() -> new Money(null, AED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Money(BigDecimal.ONE, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addsOnlyTheSameCurrency() {
        Money sum = Money.of("10.25", "AED").add(Money.of("0.75", "AED"));

        assertThat(sum).isEqualTo(Money.of("11.00", "AED"));
        assertThat(Money.zero(KWD).amount()).hasToString("0.000");
        assertThat(sum.signum()).isOne();
        assertThat(sum.isGreaterThan(Money.of("10.99", "AED"))).isTrue();
        assertThatThrownBy(() -> sum.add(Money.of("1", "JPY")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AED").hasMessageContaining("JPY");
        assertThatThrownBy(() -> sum.isGreaterThan(Money.of("1", "JPY")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Money.zero(JPY).currency()).isEqualTo(JPY);
    }
}
