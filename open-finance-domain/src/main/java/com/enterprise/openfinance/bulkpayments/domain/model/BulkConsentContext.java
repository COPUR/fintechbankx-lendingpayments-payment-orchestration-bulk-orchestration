package com.enterprise.openfinance.bulkpayments.domain.model;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

public record BulkConsentContext(
        String consentId,
        String tppId,
        Set<String> scopes,
        Instant expiresAt
) {

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
        scopes = scopes.stream().map(String::trim).map(String::toLowerCase).collect(Collectors.toUnmodifiableSet());
    }

    public boolean belongsToTpp(String candidateTppId) {
        return tppId.equals(candidateTppId);
    }

    public boolean hasScope(String requiredScope) {
        return requiredScope != null && scopes.contains(requiredScope.trim().toLowerCase());
    }

    public boolean isActive(Instant now) {
        return expiresAt.isAfter(now);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
