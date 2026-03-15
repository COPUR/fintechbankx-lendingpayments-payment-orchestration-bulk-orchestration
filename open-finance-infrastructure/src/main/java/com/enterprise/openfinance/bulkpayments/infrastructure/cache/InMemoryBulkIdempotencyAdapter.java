package com.enterprise.openfinance.bulkpayments.infrastructure.cache;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkIdempotencyRecord;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkIdempotencyPort;
import com.enterprise.openfinance.bulkpayments.infrastructure.config.BulkPaymentsCacheProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryBulkIdempotencyAdapter implements BulkIdempotencyPort {

    private final Map<String, BulkIdempotencyRecord> records = new ConcurrentHashMap<>();
    private final int maxEntries;

    public InMemoryBulkIdempotencyAdapter(BulkPaymentsCacheProperties properties) {
        this.maxEntries = properties.getMaxEntries();
    }

    @Override
    public Optional<BulkIdempotencyRecord> find(String idempotencyKey, String tppId, Instant now) {
        String key = key(idempotencyKey, tppId);
        BulkIdempotencyRecord record = records.get(key);
        if (record == null || !record.isActive(now)) {
            records.remove(key);
            return Optional.empty();
        }
        return Optional.of(record);
    }

    @Override
    public void save(BulkIdempotencyRecord record) {
        if (records.size() >= maxEntries) {
            evictOne();
        }
        records.put(key(record.idempotencyKey(), record.tppId()), record);
    }

    private static String key(String idempotencyKey, String tppId) {
        return idempotencyKey + ':' + tppId;
    }

    private void evictOne() {
        String candidate = records.keySet().stream().findFirst().orElse(null);
        if (candidate != null) {
            records.remove(candidate);
        }
    }
}
