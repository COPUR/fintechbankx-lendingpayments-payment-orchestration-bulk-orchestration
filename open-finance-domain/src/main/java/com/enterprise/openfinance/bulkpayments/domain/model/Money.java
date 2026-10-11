package com.enterprise.openfinance.bulkpayments.domain.model;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * An amount in one ISO 4217 currency, held at exactly the currency's minor units
 * (AED 2, JPY 0, KWD 3). An amount with more decimals than the currency allows is
 * refused, never rounded; there is no default currency.
 */
public record Money(BigDecimal amount, Currency currency) {

    public Money {
        if (amount == null) {
            throw new IllegalArgumentException("amount is required");
        }
        if (currency == null) {
            throw new IllegalArgumentException("currency is required");
        }
        int minorUnits = currency.getDefaultFractionDigits();
        if (minorUnits < 0) {
            throw new IllegalArgumentException("Currency " + currency.getCurrencyCode() + " has no minor unit");
        }
        if (amount.stripTrailingZeros().scale() > minorUnits) {
            throw new IllegalArgumentException("Amount " + amount.toPlainString() + " has more decimals than "
                    + currency.getCurrencyCode() + " allows " + minorUnits);
        }
        amount = amount.setScale(minorUnits);
    }

    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) > 0;
    }

    public int signum() {
        return amount.signum();
    }

    public String toPlainString() {
        return amount.toPlainString();
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Cannot combine " + currency.getCurrencyCode() + " and "
                    + other.currency.getCurrencyCode());
        }
    }
}
