package com.enterprise.openfinance.bulkpayments.infrastructure.rest;

import com.enterprise.openfinance.bulkpayments.infrastructure.rest.dto.BulkFileReportResponse;
import com.enterprise.openfinance.bulkpayments.infrastructure.rest.dto.BulkFileRequest;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps api/openapi/bulk-orchestration-service.yaml in step with the DTOs the controller
 * really reads and writes: the upload request fields (all but IntegrityMode required, Currency
 * included) and the report fields (item Amount at the currency's minor units plus Currency).
 */
class OpenApiContractTest {

    private static final Path SPEC = Path.of("..", "api", "openapi", "bulk-orchestration-service.yaml");

    @Test
    @SuppressWarnings("unchecked")
    void uploadRequestDocumentsEveryFieldTheControllerReadsAsRequired() throws Exception {
        Map<String, Object> data = (Map<String, Object>) at(spec(), "paths", "/file-payments", "post",
                "requestBody", "content", "application/json", "schema", "properties", "Data");

        List<String> fields = jsonNames(BulkFileRequest.Data.class);
        assertThat(fields).contains("Currency");
        List<String> required = fields.stream().filter(field -> !field.equals("IntegrityMode")).toList();
        assertThat((List<String>) data.get("required")).containsExactlyInAnyOrderElementsOf(required);
        assertThat(((Map<String, Object>) data.get("properties")).keySet()).containsExactlyInAnyOrderElementsOf(fields);
        assertThat(at(data, "properties", "Currency", "pattern")).isEqualTo("^[A-Z]{3}$");
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportItemsDocumentAmountAndCurrency() throws Exception {
        Map<String, Object> item = (Map<String, Object>) at(spec(), "components", "schemas", "BulkReportItem");

        assertThat(((Map<String, Object>) item.get("properties")).keySet())
                .containsExactlyInAnyOrderElementsOf(jsonNames(BulkFileReportResponse.Item.class));
        Map<String, Object> data = (Map<String, Object>) at(spec(), "components", "schemas", "BulkReportResponse",
                "properties", "Data", "properties");
        assertThat(data.keySet()).containsExactlyInAnyOrderElementsOf(jsonNames(BulkFileReportResponse.Data.class));
        assertThat(at(data, "Items", "items", "$ref")).isEqualTo("#/components/schemas/BulkReportItem");
    }

    private static List<String> jsonNames(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(component -> component.getAccessor().getAnnotation(JsonProperty.class).value())
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Object at(Map<String, Object> root, String... keys) {
        Object node = root;
        for (String key : keys) {
            assertThat(node).as(key).isInstanceOf(Map.class);
            node = ((Map<String, Object>) node).get(key);
        }
        return node;
    }

    private static Map<String, Object> spec() throws Exception {
        try (Reader reader = Files.newBufferedReader(SPEC)) {
            return new Yaml().load(reader);
        }
    }
}
