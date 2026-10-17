package com.enterprise.openfinance.bulkpayments.domain.model;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Local read model of a consent owned by the consent service
 * (fintechbankx-openfinance-consent-auth-service). Bulk payments reads it
 * through {@code BulkConsentPort}; it never stores or changes consents.
 */
public record BulkConsentContext(
        String consentId,
        String tppId,
        Set<String> scopes,
        Instant expiresAt,
        boolean authorized
) {

    /** Scope of a bulk payment consent, as consent-authorization-service names it. */
    public static final String INITIATE_BULK_PAYMENTS = "INITIATEBULKPAYMENTS";

    public BulkConsentContext {
        if (isBlank(consentId)) {
            throw new IllegalArgumentException("consentId is required");
        }
        if (isBlank(tppId)) {
            throw new IllegalArgumentException("tppId is required");
        }
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("scopes are required");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt is required");
        }

        consentId = consentId.trim();
        tppId = tppId.trim();
        scopes = scopes.stream().map(BulkConsentContext::canonical).collect(Collectors.toUnmodifiableSet());
    }

    public boolean belongsToTpp(String candidateTppId) {
        return tppId.equals(candidateTppId);
    }

    public boolean hasScope(String requiredScope) {
        return requiredScope != null && scopes.contains(canonical(requiredScope));
    }

    /** The consent authorises bulk payment files (scope INITIATEBULKPAYMENTS). */
    public boolean allowsBulkInitiation() {
        return hasScope(INITIATE_BULK_PAYMENTS);
    }

    /** Same normalisation as consent-authorization-service: upper case, anything but A-Z and 0-9 dropped. */
    private static String canonical(String scope) {
        return scope.trim().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    public boolean isActive(Instant now) {
        return expiresAt.isAfter(now);
    }

    /** The PSU authorised the consent and has not revoked it. */
    public boolean isAuthorized() {
        return authorized;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
