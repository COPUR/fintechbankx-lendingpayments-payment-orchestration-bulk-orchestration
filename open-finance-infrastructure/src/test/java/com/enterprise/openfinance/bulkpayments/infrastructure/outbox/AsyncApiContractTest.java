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
 * as a consumer validating with the spec would.
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
    void rejectedEnvelopeMatchesTheSpec() throws Exception {
        Map<String, Object> schemas = schemas();
        JsonNode envelope = envelope(factory.toOutboxRow(
                new BulkFileRejected(UUID.randomUUID(), "FILE-2", 1L, AT, 2, 2), "FILE-2"));

        assertEnvelope(schemas, envelope, "BulkFileRejectedData");
    }

    @SuppressWarnings("unchecked")
    private static void assertEnvelope(Map<String, Object> schemas, JsonNode envelope, String dataSchema) {
        Map<String, Object> envelopeSchema = (Map<String, Object>) schemas.get("EventEnvelope");
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
