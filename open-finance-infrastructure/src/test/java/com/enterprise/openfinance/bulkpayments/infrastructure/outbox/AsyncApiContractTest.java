package com.enterprise.openfinance.bulkpayments.infrastructure.outbox;

import com.enterprise.openfinance.bulkpayments.domain.event.BulkFileAccepted;
import com.enterprise.openfinance.bulkpayments.domain.event.BulkFileRejected;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkIntegrityMode;
import com.enterprise.openfinance.bulkpayments.domain.model.Money;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks real envelopes from {@link BulkFileEventEnvelopeFactory} against the provider
 * AsyncAPI spec: required fields and the regex patterns (eventType, DecimalAmount),
 * as a consumer validating with the spec would, and the one aggregate channel.
 */
class AsyncApiContractTest {

    private static final Path SPEC = Path.of("..", "api", "asyncapi", "svc-pay-bulk-orchestration.yaml");
    private static final Instant AT = Instant.parse("2026-10-08T10:00:00Z");

    private final ObjectMapper json = new ObjectMapper();
    private final BulkFileEventEnvelopeFactory factory = new BulkFileEventEnvelopeFactory(json);

    @Test
    void acceptedEnvelopesMatchTheSpecForEveryMinorUnit() throws Exception {
        Map<String, Object> schemas = schemas();
        assertThat(required(schemas, "BulkFileAcceptedData")).contains("totalAmount", "currency");
        for (Money amount : List.of(Money.of("1500", "JPY"), Money.of("1500.00", "AED"), Money.of("1.234", "KWD"))) {
            JsonNode envelope = envelope(factory.toOutboxRow(new BulkFileAccepted(UUID.randomUUID(), "FILE-1", 0L, AT,
                    "CONS-1", "TPP-001", BulkIntegrityMode.PARTIAL_REJECTION, 1, 1, 0, amount), "ix-1"));

            assertEnvelope(schemas, envelope, "BulkFileAcceptedData");
            assertThat(envelope.at("/data/totalAmount").asText()).matches(pattern(schemas, "DecimalAmount"));
            assertThat(envelope.at("/data/currency").asText()).matches(pattern(schemas, "CurrencyCode"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectedEnvelopesMatchTheSpecForEveryReason() throws Exception {
        Map<String, Object> schemas = schemas();
        assertThat(required(schemas, "BulkFileRejectedData")).contains("reason");
        List<String> reasons = (List<String>) ((Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>)
                schemas.get("BulkFileRejectedData")).get("properties")).get("reason")).get("enum");
        for (BulkFileRejected.Reason reason : BulkFileRejected.Reason.values()) {
            JsonNode envelope = envelope(factory.toOutboxRow(
                    new BulkFileRejected(UUID.randomUUID(), "FILE-2", 1L, AT, 2, 2, reason), "FILE-2"));

            assertEnvelope(schemas, envelope, "BulkFileRejectedData");
            assertThat(reasons).contains(envelope.at("/data/reason").asText());
        }
    }

    /** One topic per aggregate (ADR-019): one channel carries every BulkFile event type, named by the eventType header. */
    @Test
    @SuppressWarnings("unchecked")
    void oneAggregateChannelCarriesEveryEventTypeWithItsHeaderConst() throws Exception {
        Map<String, Object> spec;
        try (Reader reader = Files.newBufferedReader(SPEC)) {
            spec = new Yaml().load(reader);
        }
        Map<String, Map<String, Object>> channels = (Map<String, Map<String, Object>>) spec.get("channels");
        assertThat(channels).hasSize(1);
        Map<String, Object> channel = channels.values().iterator().next();
        assertThat(channel.get("address")).isEqualTo("evt.pay.bulk.v1");
        assertThat(((Map<String, Map<String, Object>>) channel.get("bindings")).get("kafka").get("topic"))
                .isEqualTo("evt.pay.bulk.v1");
        Map<String, Map<String, Object>> messages = (Map<String, Map<String, Object>>)
                ((Map<String, Object>) spec.get("components")).get("messages");
        Map<String, Map<String, Object>> onChannel = (Map<String, Map<String, Object>>) channel.get("messages");
        assertThat(onChannel.keySet()).containsExactlyInAnyOrder("BulkFileAccepted", "BulkFileCompleted", "BulkFileRejected");
        for (String name : onChannel.keySet()) {
            Map<String, Object> message = messages.get(name);
            Object type = constOf(message.get("payload"), "eventType");
            assertThat(type).as(name).isEqualTo("Payments." + name.replace("BulkFile", "BulkFile.") + ".v1");
            assertThat(constOf(message.get("headers"), "eventType")).as("%s eventType header", name).isEqualTo(type);
            assertThat((List<Object>) ((Map<String, Object>) message.get("headers")).get("allOf"))
                    .first().isEqualTo(Map.of("$ref", "#/components/schemas/EventHeaders"));
        }
        OutboxEventJpaEntity row = factory.toOutboxRow(new BulkFileRejected(UUID.randomUUID(), "FILE-2", 1L, AT, 2, 2,
                BulkFileRejected.Reason.ALL_ITEMS_REJECTED), "ix-1");
        assertThat(OutboxRelay.toRecord(row).topic()).isEqualTo(channel.get("address"));
    }

    @SuppressWarnings("unchecked")
    private static Object constOf(Object schema, String property) {
        List<Map<String, Object>> parts = (List<Map<String, Object>>) ((Map<String, Object>) schema).get("allOf");
        assertThat(parts).as("allOf schema").isNotNull();
        for (Map<String, Object> part : parts) {
            Map<String, Map<String, Object>> props = (Map<String, Map<String, Object>>) part.get("properties");
            if (props != null && props.containsKey(property) && props.get(property).containsKey("const")) {
                return props.get(property).get("const");
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void assertEnvelope(Map<String, Object> schemas, JsonNode envelope, String dataSchema) throws Exception {
        assertThat(schemas.get("EventEnvelope")).isEqualTo(Map.of("$ref", "./common/event-envelope.yaml#/EventEnvelope"));
        Map<String, Object> envelopeSchema;
        try (Reader reader = Files.newBufferedReader(SPEC.resolveSibling(Path.of("common", "event-envelope.yaml")))) {
            envelopeSchema = (Map<String, Object>) ((Map<String, Object>) new Yaml().load(reader)).get("EventEnvelope");
        }
        List<String> required = (List<String>) envelopeSchema.get("required");
        assertThat(required).allSatisfy(field -> assertThat(envelope.has(field)).as(field).isTrue());
        Map<String, Object> properties = (Map<String, Object>) envelopeSchema.get("properties");
        String eventTypePattern = (String) ((Map<String, Object>) properties.get("eventType")).get("pattern");
        assertThat(envelope.get("eventType").asText()).matches(eventTypePattern);

        List<String> dataRequired = required(schemas, dataSchema);
        assertThat(dataRequired).allSatisfy(field -> assertThat(envelope.get("data").has(field)).as(field).isTrue());
    }

    @SuppressWarnings("unchecked")
    private static List<String> required(Map<String, Object> schemas, String name) {
        return (List<String>) ((Map<String, Object>) schemas.get(name)).get("required");
    }

    @SuppressWarnings("unchecked")
    private static String pattern(Map<String, Object> schemas, String name) {
        return (String) ((Map<String, Object>) schemas.get(name)).get("pattern");
    }

    private JsonNode envelope(OutboxEventJpaEntity row) throws Exception {
        return json.readTree(row.getPayload());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schemas() throws Exception {
        try (Reader reader = Files.newBufferedReader(SPEC)) {
            Map<String, Object> spec = new Yaml().load(reader);
            return (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");
        }
    }
}
