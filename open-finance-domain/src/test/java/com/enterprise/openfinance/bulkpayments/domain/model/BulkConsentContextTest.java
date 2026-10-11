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
                Set.of("INITIATEBULKPAYMENTS"),
                Instant.parse("2099-01-01T00:00:00Z"),
                true
        );

        assertThat(consent.belongsToTpp("TPP-001")).isTrue();
        assertThat(consent.belongsToTpp("TPP-999")).isFalse();
        assertThat(consent.allowsBulkInitiation()).isTrue();
        assertThat(consent.hasScope("INITIATEBULKPAYMENTS")).isTrue();
        assertThat(consent.hasScope("READBALANCES")).isFalse();
        assertThat(consent.isActive(Instant.parse("2026-02-09T00:00:00Z"))).isTrue();
        assertThat(consent.isActive(Instant.parse("2100-01-01T00:00:00Z"))).isFalse();
        assertThat(consent.isAuthorized()).isTrue();
        assertThat(new BulkConsentContext("CONS-BULK-002", "TPP-001", Set.of("INITIATEBULKPAYMENTS"),
                Instant.parse("2099-01-01T00:00:00Z"), false).isAuthorized()).isFalse();
    }

    @Test
    void scopesAreTheConsentServicesCanonicalNames() {
        assertThat(BulkConsentContext.INITIATE_BULK_PAYMENTS).isEqualTo("INITIATEBULKPAYMENTS");
        // Normalised as consent-authorization-service does: upper case, separators dropped.
        assertThat(consent(Set.of("initiate-bulk-payments")).allowsBulkInitiation()).isTrue();
        assertThat(consent(Set.of("initiate_bulk_payments")).hasScope("InitiateBulkPayments")).isTrue();
        // The seed's made-up scope and single-payment initiation do not authorise a bulk file.
        assertThat(consent(Set.of("bulk-payment")).allowsBulkInitiation()).isFalse();
        assertThat(consent(Set.of("INITIATEPAYMENTS", "READACCOUNTS")).allowsBulkInitiation()).isFalse();
    }

    private static BulkConsentContext consent(Set<String> scopes) {
        return new BulkConsentContext("CONS-1", "TPP-001", scopes, Instant.parse("2099-01-01T00:00:00Z"), true);
    }

    @Test
    void shouldRejectInvalidConsentContext() {
        assertInvalid("", "TPP-001", Set.of("INITIATEBULKPAYMENTS"), Instant.parse("2099-01-01T00:00:00Z"), "consentId");
        assertInvalid("CONS-BULK-001", "", Set.of("INITIATEBULKPAYMENTS"), Instant.parse("2099-01-01T00:00:00Z"), "tppId");
        assertInvalid("CONS-BULK-001", "TPP-001", null, Instant.parse("2099-01-01T00:00:00Z"), "scopes");
        assertInvalid("CONS-BULK-001", "TPP-001", Set.of(), Instant.parse("2099-01-01T00:00:00Z"), "scopes");
        assertInvalid("CONS-BULK-001", "TPP-001", Set.of("INITIATEBULKPAYMENTS"), null, "expiresAt");
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
                expiresAt,
                true
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedField);
    }
}
