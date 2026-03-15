package com.enterprise.openfinance.bulkpayments.domain.port.out;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkConsentContext;

import java.util.Optional;

public interface BulkConsentPort {
    Optional<BulkConsentContext> findById(String consentId);
}
