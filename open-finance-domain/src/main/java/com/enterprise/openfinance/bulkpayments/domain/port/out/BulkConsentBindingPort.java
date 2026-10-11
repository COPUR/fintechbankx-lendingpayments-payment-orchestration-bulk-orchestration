package com.enterprise.openfinance.bulkpayments.domain.port.out;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkConsentBinding;

import java.util.Optional;

/** Single-use consent bindings owned by this service. */
public interface BulkConsentBindingPort {

    /**
     * Binds the consent to the file atomically.
     *
     * @return false when the consent is already bound to a file (also when a concurrent
     *         upload bound it first)
     */
    boolean bind(BulkConsentBinding binding);

    Optional<BulkConsentBinding> findByConsentId(String consentId);
}
