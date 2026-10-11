package com.enterprise.openfinance.bulkpayments.infrastructure.outbox;

import com.enterprise.openfinance.bulkpayments.domain.event.BulkFileAccepted;
import com.enterprise.openfinance.bulkpayments.domain.event.BulkFileRejected;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkFileStatus;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkIntegrityMode;
import com.enterprise.openfinance.bulkpayments.domain.model.Money;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BulkFileEventEnvelopeFactoryTest {

    private static final Instant AT = Instant.parse("2026-02-09T10:00:00Z");
    private final ObjectMapper json = new ObjectMapper();
    private final BulkFileEventEnvelopeFactory factory = new BulkFileEventEnvelopeFactory(json);

    @Test
    void acceptedEventEnvelope() throws Exception {
        UUID eventId = UUID.randomUUID();
        OutboxEventJpaEntity row = factory.toOutboxRow(new BulkFileAccepted(eventId, "FILE-1", 0L, AT, "CONS-1",
                "TPP-001", BulkIntegrityMode.PARTIAL_REJECTION, 3, 2, 1, Money.of("350", "AED")), "ix-1");

        assertThat(row.getEventType()).isEqualTo("Payments.BulkFile.Accepted.v1");
        assertThat(row.getAggregateType()).isEqualTo("BulkFile");
        assertThat(row.getAggregateId()).isEqualTo("FILE-1");
        assertThat(row.getEventId()).isEqualTo(eventId);
        assertThat(row.getStatus()).isEqualTo(OutboxEventJpaEntity.PENDING);

        JsonNode envelope = json.readTree(row.getPayload());
        assertThat(envelope.fieldNames()).toIterable().containsExactly("eventId", "eventType", "occurredAt",
                "aggregateId", "aggregateVersion", "correlationId", "causationId", "producer", "data");
        assertThat(envelope.get("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(envelope.get("occurredAt").asText()).isEqualTo("2026-02-09T10:00:00Z");
        assertThat(envelope.get("aggregateVersion").asLong()).isZero();
        assertThat(envelope.get("correlationId").asText()).isEqualTo("ix-1");
        assertThat(envelope.get("causationId").isNull()).isTrue();
        assertThat(envelope.get("producer").asText()).isEqualTo("svc-pay-bulk-orchestration");
        JsonNode data = envelope.get("data");
        assertThat(data.get("fileId").asText()).isEqualTo("FILE-1");
        assertThat(data.get("consentId").asText()).isEqualTo("CONS-1");
        assertThat(data.get("tppId").asText()).isEqualTo("TPP-001");
        assertThat(data.get("integrityMode").asText()).isEqualTo("PARTIAL_REJECTION");
        assertThat(data.get("totalCount").asInt()).isEqualTo(3);
        assertThat(data.get("totalAmount").asText()).isEqualTo("350.00");
        assertThat(data.get("currency").asText()).isEqualTo("AED");
        assertThat(data.has("payeeIban")).isFalse();
    }

    @Test
    void rejectedEnvelope() throws Exception {
        OutboxEventJpaEntity rejected = factory.toOutboxRow(new BulkFileRejected(UUID.randomUUID(), "FILE-2", 1L, AT,
                2, 2, BulkFileRejected.Reason.CONSENT_NOT_USABLE), "FILE-2");
        assertThat(rejected.getEventType()).isEqualTo("Payments.BulkFile.Rejected.v1");
        assertThat(json.readTree(rejected.getPayload()).get("data").get("rejectedCount").asInt()).isEqualTo(2);
        assertThat(json.readTree(rejected.getPayload()).get("data").get("reason").asText())
                .isEqualTo("CONSENT_NOT_USABLE");
    }

    /** One topic per aggregate (ADR-019): every BulkFile event type goes to evt.pay.bulk.v1. */
    @Test
    void everyEventTypeGoesToTheAggregateTopic() {
        List<OutboxEventJpaEntity> rows = List.of(
                factory.toOutboxRow(new BulkFileAccepted(UUID.randomUUID(), "F", 0L, AT, "C", "T",
                        BulkIntegrityMode.FULL_REJECTION, 1, 1, 0, Money.of("1", "AED")), "F"),
                factory.toOutboxRow(new BulkFileRejected(UUID.randomUUID(), "F", 1L, AT, 1, 1,
                        BulkFileRejected.Reason.ALL_ITEMS_REJECTED), "F"));

        for (OutboxEventJpaEntity row : rows) {
            assertThat(OutboxRelay.toRecord(row).topic()).isEqualTo("evt.pay.bulk.v1");
            assertThat(row.getEventType()).startsWith("Payments.BulkFile.").endsWith(".v1");
        }
    }

    @Test
    void amountsAreWrittenAtTheCurrencyMinorUnits() throws Exception {
        for (String[] c : new String[][] {{"1500", "JPY", "1500"}, {"1.234", "KWD", "1.234"}, {"10", "AED", "10.00"}}) {
            OutboxEventJpaEntity row = factory.toOutboxRow(new BulkFileAccepted(UUID.randomUUID(), "F", 0L, AT, "C",
                    "T", BulkIntegrityMode.PARTIAL_REJECTION, 1, 1, 0, Money.of(c[0], c[1])), "F");
            JsonNode data = json.readTree(row.getPayload()).get("data");
            assertThat(data.get("totalAmount").asText()).isEqualTo(c[2]);
            assertThat(data.get("currency").asText()).isEqualTo(c[1]);
        }
    }
}
