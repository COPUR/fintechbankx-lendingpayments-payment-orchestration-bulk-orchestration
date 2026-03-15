package com.enterprise.openfinance.bulkpayments.infrastructure.persistence;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkConsentContext;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkConsentPort;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryBulkConsentAdapter implements BulkConsentPort {

    private final Map<String, BulkConsentContext> data = new ConcurrentHashMap<>();

    public InMemoryBulkConsentAdapter() {
        data.put("CONS-BULK-001", new BulkConsentContext(
                "CONS-BULK-001",
                "TPP-001",
                Set.of("bulk-payment"),
                Instant.parse("2099-01-01T00:00:00Z")
        ));

        data.put("CONS-BULK-EXPIRED", new BulkConsentContext(
                "CONS-BULK-EXPIRED",
                "TPP-001",
                Set.of("bulk-payment"),
                Instant.parse("2026-01-01T00:00:00Z")
        ));

        data.put("CONS-BULK-RO", new BulkConsentContext(
                "CONS-BULK-RO",
                "TPP-001",
                Set.of("read-accounts"),
                Instant.parse("2099-01-01T00:00:00Z")
        ));
    }

    @Override
    public Optional<BulkConsentContext> findById(String consentId) {
        return Optional.ofNullable(data.get(consentId));
    }
}
