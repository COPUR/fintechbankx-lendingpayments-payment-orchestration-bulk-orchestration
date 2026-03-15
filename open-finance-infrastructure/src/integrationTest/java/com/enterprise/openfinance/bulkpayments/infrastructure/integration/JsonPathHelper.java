package com.enterprise.openfinance.bulkpayments.infrastructure.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class JsonPathHelper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private JsonPathHelper() {
    }

    static String read(String json, String path) throws Exception {
        JsonNode node = OBJECT_MAPPER.readTree(json);
        String[] parts = path.replace("$.", "").split("\\.");
        for (String part : parts) {
            node = node.path(part);
        }
        return node.asText();
    }
}
