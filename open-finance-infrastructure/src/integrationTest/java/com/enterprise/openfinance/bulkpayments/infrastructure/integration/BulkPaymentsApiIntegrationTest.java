package com.enterprise.openfinance.bulkpayments.infrastructure.integration;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
@SpringBootTest(
        classes = BulkPaymentsApiIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration"
)
@AutoConfigureMockMvc(addFilters = false)
class BulkPaymentsApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldUploadAndSupportIdempotencyStatusProgressAndReportEtag() throws Exception {
        String requestBody = validBody(
                "CONS-BULK-001",
                "payroll.csv",
                "instruction_id,payee_iban,amount\nINS-1,AE120001000000000000000001,10.00",
                "01fWq3K7Vfc7kU3kIWw7y-77cUZ1vcD9lWVs5ICT-tA",
                "PARTIAL_REJECTION"
        );

        MvcResult firstUpload = mockMvc.perform(withHeaders(
                        post("/open-finance/v1/file-payments")
                                .header("x-idempotency-key", "IDEMP-INT-001")
                                .contentType("application/json")
                                .content(requestBody)
                ))
                .andExpect(status().isAccepted())
                .andExpect(header().string("X-OF-Idempotency", "MISS"))
                .andExpect(jsonPath("$.Data.FilePaymentId").exists())
                .andReturn();

        String fileId = JsonPathHelper.read(firstUpload.getResponse().getContentAsString(), "$.Data.FilePaymentId");

        mockMvc.perform(withHeaders(
                        post("/open-finance/v1/file-payments")
                                .header("x-idempotency-key", "IDEMP-INT-001")
                                .contentType("application/json")
                                .content(requestBody)
                ))
                .andExpect(status().isAccepted())
                .andExpect(header().string("X-OF-Idempotency", "HIT"))
                .andExpect(jsonPath("$.Data.FilePaymentId").value(fileId));

        mockMvc.perform(withHeaders(get("/open-finance/v1/file-payments/{fileId}", fileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Data.Status").value("Processing"));

        MvcResult secondStatus = mockMvc.perform(withHeaders(get("/open-finance/v1/file-payments/{fileId}", fileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Data.Status").value("Completed"))
                .andExpect(header().exists("ETag"))
                .andReturn();

        MvcResult reportResult = mockMvc.perform(withHeaders(get("/open-finance/v1/file-payments/{fileId}/report", fileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Data.AcceptedCount").value(1))
                .andExpect(header().exists("ETag"))
                .andReturn();

        String reportEtag = reportResult.getResponse().getHeader("ETag");
        mockMvc.perform(withHeaders(get("/open-finance/v1/file-payments/{fileId}/report", fileId)
                        .header("If-None-Match", reportEtag)))
                .andExpect(status().isNotModified());

        String statusEtag = secondStatus.getResponse().getHeader("ETag");
        mockMvc.perform(withHeaders(get("/open-finance/v1/file-payments/{fileId}", fileId)
                        .header("If-None-Match", statusEtag)))
                .andExpect(status().isNotModified());
    }

    @Test
    void shouldRejectInvalidPayloadsAndSecurityViolations() throws Exception {
        mockMvc.perform(withHeaders(
                        post("/open-finance/v1/file-payments")
                                .header("x-idempotency-key", "IDEMP-INT-002")
                                .contentType("application/json")
                                .content(validBody("CONS-BULK-001", "empty.csv", "", "x", "PARTIAL_REJECTION"))
                ))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));

        mockMvc.perform(withHeaders(
                        post("/open-finance/v1/file-payments")
                                .header("x-idempotency-key", "IDEMP-INT-003")
                                .contentType("application/json")
                                .content(validBody("CONS-BULK-001", "bad.csv", "bad_header\nINS-1,AE120001000000000000000001,10.00", "yzBE-rNPlyh-11rrk255v7Z5Vh2fgwS5GAK8CBW5qfs", "PARTIAL_REJECTION"))
                ))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("Schema Validation Failed")));

        mockMvc.perform(withHeaders(
                        post("/open-finance/v1/file-payments")
                                .header("x-idempotency-key", "IDEMP-INT-004")
                                .contentType("application/json")
                                .content(validBody("CONS-BULK-001", "payroll.csv", "instruction_id,payee_iban,amount\nINS-1,AE120001000000000000000001,10.00", "wrong", "PARTIAL_REJECTION"))
                ))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("Integrity Failure")));
    }

    @Test
    void shouldSupportPartialAndFullRejectionModes() throws Exception {
        String mixed = "instruction_id,payee_iban,amount\nINS-1,AE120001000000000000000001,10.00\nINS-2,AE000,10.00";

        MvcResult partialUpload = mockMvc.perform(withHeaders(
                        post("/open-finance/v1/file-payments")
                                .header("x-idempotency-key", "IDEMP-INT-005")
                                .contentType("application/json")
                                .content(validBody("CONS-BULK-001", "partial.csv", mixed, "7U_URRvEOLkena3QeY49AmC-I9-MJZV3RH-KsWGHpUs", "PARTIAL_REJECTION"))
                ))
                .andExpect(status().isAccepted())
                .andReturn();

        String partialId = JsonPathHelper.read(partialUpload.getResponse().getContentAsString(), "$.Data.FilePaymentId");
        mockMvc.perform(withHeaders(get("/open-finance/v1/file-payments/{fileId}", partialId)));
        mockMvc.perform(withHeaders(get("/open-finance/v1/file-payments/{fileId}", partialId)));

        mockMvc.perform(withHeaders(get("/open-finance/v1/file-payments/{fileId}/report", partialId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Data.Status").value("PartiallyAccepted"));

        MvcResult fullUpload = mockMvc.perform(withHeaders(
                        post("/open-finance/v1/file-payments")
                                .header("x-idempotency-key", "IDEMP-INT-006")
                                .contentType("application/json")
                                .content(validBody("CONS-BULK-001", "full.csv", mixed, "7U_URRvEOLkena3QeY49AmC-I9-MJZV3RH-KsWGHpUs", "FULL_REJECTION"))
                ))
                .andExpect(status().isAccepted())
                .andReturn();

        String fullId = JsonPathHelper.read(fullUpload.getResponse().getContentAsString(), "$.Data.FilePaymentId");
        mockMvc.perform(withHeaders(get("/open-finance/v1/file-payments/{fileId}", fullId)));
        mockMvc.perform(withHeaders(get("/open-finance/v1/file-payments/{fileId}", fullId)));

        mockMvc.perform(withHeaders(get("/open-finance/v1/file-payments/{fileId}/report", fullId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Data.Status").value("Rejected"));
    }

    private static MockHttpServletRequestBuilder withHeaders(MockHttpServletRequestBuilder builder) {
        return builder
                .header("Authorization", "DPoP integration-token")
                .header("DPoP", "proof-jwt")
                .header("X-FAPI-Interaction-ID", "ix-bulk-payments-integration")
                .header("x-fapi-financial-id", "TPP-001")
                .accept("application/json");
    }

    private static String validBody(String consentId,
                                    String fileName,
                                    String fileContent,
                                    String hash,
                                    String integrityMode) {
        return """
                {
                  "Data": {
                    "ConsentId": "%s",
                    "FileName": "%s",
                    "FileContent": "%s",
                    "FileHash": "%s",
                    "IntegrityMode": "%s"
                  }
                }
                """.formatted(
                jsonEscape(consentId),
                jsonEscape(fileName),
                jsonEscape(fileContent),
                jsonEscape(hash),
                jsonEscape(integrityMode)
        );
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            SecurityAutoConfiguration.class,
            OAuth2ResourceServerAutoConfiguration.class,
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            MongoAutoConfiguration.class,
            MongoDataAutoConfiguration.class,
            RedisAutoConfiguration.class,
            RedisRepositoriesAutoConfiguration.class
    })
    @ComponentScan(basePackages = {
            "com.enterprise.openfinance.bulkpayments.application",
            "com.enterprise.openfinance.bulkpayments.infrastructure"
    })
    static class TestApplication {
    }
}
