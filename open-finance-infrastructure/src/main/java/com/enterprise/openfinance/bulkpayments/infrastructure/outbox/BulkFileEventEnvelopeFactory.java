package com.enterprise.openfinance.bulkpayments.infrastructure.outbox;

import com.enterprise.openfinance.bulkpayments.domain.event.BulkFileAccepted;
import com.enterprise.openfinance.bulkpayments.domain.event.BulkFileEvent;
import com.enterprise.openfinance.bulkpayments.domain.event.BulkFileRejected;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns BulkFile domain events into the public envelope of
 * api/asyncapi/svc-pay-bulk-orchestration.yaml: topic
 * evt.pay.bulk.&lt;event&gt;.v1, eventType Payments.BulkFile.&lt;Event&gt;.v1,
 * amounts as decimal strings, ids and counts only (no payee IBANs).
 */
public class BulkFileEventEnvelopeFactory {

    public static final String PRODUCER = "svc-pay-bulk-orchestration";
    public static final String AGGREGATE_TYPE = "BulkFile";

    private final ObjectMapper objectMapper;

    public BulkFileEventEnvelopeFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public OutboxEventJpaEntity toOutboxRow(BulkFileEvent event, String correlationId) {
        PublicEvent mapped = map(event);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", event.eventId().toString());
        envelope.put("eventType", mapped.eventType());
        envelope.put("occurredAt", event.occurredAt().toString());
        envelope.put("aggregateId", event.fileId());
        envelope.put("aggregateVersion", event.aggregateVersion());
        envelope.put("correlationId", correlationId);
        envelope.put("causationId", null);
        envelope.put("producer", PRODUCER);
        envelope.put("data", mapped.data());

        return new OutboxEventJpaEntity(event.eventId(), AGGREGATE_TYPE, event.fileId(), event.aggregateVersion(),
                mapped.eventType(), mapped.topic(), toJson(envelope), correlationId, event.occurredAt());
    }

    static PublicEvent map(BulkFileEvent event) {
        return switch (event) {
            case BulkFileAccepted e -> new PublicEvent("accepted", "Accepted", data(
                    "fileId", e.fileId(),
                    "consentId", e.consentId(),
                    "tppId", e.tppId(),
                    "integrityMode", e.integrityMode().name(),
                    "totalCount", e.totalCount(),
                    "acceptedCount", e.acceptedCount(),
                    "rejectedCount", e.rejectedCount(),
                    "totalAmount", amount(e.totalAmount())));
            case BulkFileRejected e -> new PublicEvent("rejected", "Rejected", data(
                    "fileId", e.fileId(),
                    "totalCount", e.totalCount(),
                    "rejectedCount", e.rejectedCount(),
                    "rejectedAt", e.occurredAt().toString()));
        };
    }

    /** Decimal string with at least two decimals and no precision lost: 10 -> "10.00", 2510.355 -> "2510.355". */
    static String amount(BigDecimal value) {
        int scale = Math.max(2, value.stripTrailingZeros().scale());
        return value.setScale(scale, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static Map<String, Object> data(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private String toJson(Map<String, Object> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise bulk file event envelope", e);
        }
    }

    record PublicEvent(String topicSuffix, String eventName, Map<String, Object> data) {
        String topic() {
            return "evt.pay.bulk." + topicSuffix + ".v1";
        }

        String eventType() {
            return "Payments.BulkFile." + eventName + ".v1";
        }
    }
}
