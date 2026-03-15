package com.enterprise.openfinance.bulkpayments.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BulkConsentContextTest {

    @Test
    void shouldValidateOwnershipScopeAndExpiry() {
        BulkConsentContext consent = new BulkConsentContext(
                "CONS-BULK-001",
                "TPP-001",
                Set.of("bulk-payment"),
                Instant.parse("2099-01-01T00:00:00Z")
        );

        assertThat(consent.belongsToTpp("TPP-001")).isTrue();
        assertThat(consent.belongsToTpp("TPP-999")).isFalse();
        assertThat(consent.hasScope("bulk-payment")).isTrue();
        assertThat(consent.hasScope("read-balances")).isFalse();
        assertThat(consent.isActive(Instant.parse("2026-02-09T00:00:00Z"))).isTrue();
        assertThat(consent.isActive(Instant.parse("2100-01-01T00:00:00Z"))).isFalse();
    }

    @Test
    void shouldRejectInvalidConsentContext() {
        assertInvalid("", "TPP-001", Set.of("bulk-payment"), Instant.parse("2099-01-01T00:00:00Z"), "consentId");
        assertInvalid("CONS-BULK-001", "", Set.of("bulk-payment"), Instant.parse("2099-01-01T00:00:00Z"), "tppId");
        assertInvalid("CONS-BULK-001", "TPP-001", null, Instant.parse("2099-01-01T00:00:00Z"), "scopes");
        assertInvalid("CONS-BULK-001", "TPP-001", Set.of(), Instant.parse("2099-01-01T00:00:00Z"), "scopes");
        assertInvalid("CONS-BULK-001", "TPP-001", Set.of("bulk-payment"), null, "expiresAt");
    }

    private static void assertInvalid(String consentId,
                                      String tppId,
                                      Set<String> scopes,
                                      Instant expiresAt,
                                      String expectedField) {
        assertThatThrownBy(() -> new BulkConsentContext(
                consentId,
                tppId,
                scopes,
                expiresAt
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedField);
    }
}
