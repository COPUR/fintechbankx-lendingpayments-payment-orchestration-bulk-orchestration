package com.enterprise.openfinance.bulkpayments.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Single use of a bulk payment consent: one consent authorises one file. The binding
 * records what was authorised against it (file hash, item count and control sum), so
 * a second file on the same consent can be refused and the first one audited. The
 * consent read from the consent service carries no such limits, so this service keeps them.
 */
public record BulkConsentBinding(
        String consentId,
        String tppId,
        String fileId,
        String fileHash,
        int itemCount,
        Money controlSum,
        Instant boundAt
) {

    public BulkConsentBinding {
        requireText(consentId, "consentId");
        requireText(tppId, "tppId");
        requireText(fileId, "fileId");
        requireText(fileHash, "fileHash");
        if (itemCount <= 0) {
            throw new IllegalArgumentException("itemCount must be positive");
        }
        if (controlSum == null) {
            throw new IllegalArgumentException("controlSum is required");
        }
        Objects.requireNonNull(boundAt, "boundAt");
    }

    /** Binds the file's consent to it; every line counts, including lines rejected at validation. */
    public static BulkConsentBinding of(BulkFile file, String fileHash, Instant boundAt) {
        return new BulkConsentBinding(file.consentId(), file.tppId(), file.fileId(), fileHash, file.totalCount(),
                file.totalAmount(), boundAt);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
