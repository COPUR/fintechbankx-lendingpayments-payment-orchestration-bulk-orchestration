package com.enterprise.openfinance.bulkpayments.infrastructure.functional;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.ComponentScan;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

@Tag("functional")
@Tag("e2e")
@Tag("uat")
@SpringBootTest(
        classes = BulkPaymentsUatTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration"
)
class BulkPaymentsUatTest {

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    @Test
    void shouldCompleteBulkUploadToReportJourney() {
        Response upload = request()
                .header("x-idempotency-key", "IDEMP-UAT-001")
                .contentType("application/json")
                .body(validBody(
                        "CONS-BULK-001",
                        "payroll.csv",
                        "instruction_id,payee_iban,amount\nINS-1,AE120001000000000000000001,10.00",
                        "01fWq3K7Vfc7kU3kIWw7y-77cUZ1vcD9lWVs5ICT-tA",
                        "PARTIAL_REJECTION"
                ))
                .when()
                .post("/open-finance/v1/file-payments")
                .then()
                .statusCode(202)
                .extract()
                .response();

        String fileId = upload.path("Data.FilePaymentId");

        request()
                .when()
                .get("/open-finance/v1/file-payments/{fileId}", fileId)
                .then()
                .statusCode(200)
                .body("Data.Status", equalTo("Processing"));

        request()
                .when()
                .get("/open-finance/v1/file-payments/{fileId}", fileId)
                .then()
                .statusCode(200)
                .body("Data.Status", equalTo("Completed"));

        request()
                .when()
                .get("/open-finance/v1/file-payments/{fileId}/report", fileId)
                .then()
                .statusCode(200)
                .body("Data.AcceptedCount", equalTo(1))
                .body("Data.RejectedCount", equalTo(0));
    }

    @Test
    void shouldSupportIdempotentReplayAndPartialFailure() {
        String mixedBody = validBody(
                "CONS-BULK-001",
                "mixed.csv",
                "instruction_id,payee_iban,amount\nINS-1,AE120001000000000000000001,10.00\nINS-2,AE000,10.00",
                "7U_URRvEOLkena3QeY49AmC-I9-MJZV3RH-KsWGHpUs",
                "PARTIAL_REJECTION"
        );

        Response firstUpload = request()
                .header("x-idempotency-key", "IDEMP-UAT-002")
                .contentType("application/json")
                .body(mixedBody)
                .when()
                .post("/open-finance/v1/file-payments")
                .then()
                .statusCode(202)
                .header("X-OF-Idempotency", equalTo("MISS"))
                .extract()
                .response();

        String fileId = firstUpload.path("Data.FilePaymentId");

        request()
                .header("x-idempotency-key", "IDEMP-UAT-002")
                .contentType("application/json")
                .body(mixedBody)
                .when()
                .post("/open-finance/v1/file-payments")
                .then()
                .statusCode(202)
                .header("X-OF-Idempotency", equalTo("HIT"))
                .body("Data.FilePaymentId", equalTo(fileId));

        request().when().get("/open-finance/v1/file-payments/{fileId}", fileId);
        request().when().get("/open-finance/v1/file-payments/{fileId}", fileId)
                .then()
                .statusCode(200)
                .body("Data.Status", equalTo("PartiallyAccepted"));

        request()
                .when()
                .get("/open-finance/v1/file-payments/{fileId}/report", fileId)
                .then()
                .statusCode(200)
                .body("Data.AcceptedCount", equalTo(1))
                .body("Data.RejectedCount", equalTo(1))
                .body("Data.Items.size()", greaterThanOrEqualTo(2));
    }

    @Test
    void shouldRejectInvalidPayloads() {
        request()
                .header("x-idempotency-key", "IDEMP-UAT-003")
                .contentType("application/json")
                .body(validBody("CONS-BULK-001", "bad.csv", "", "x", "PARTIAL_REJECTION"))
                .when()
                .post("/open-finance/v1/file-payments")
                .then()
                .statusCode(400)
                .body("code", equalTo("BUSINESS_RULE_VIOLATION"));

        request()
                .header("x-idempotency-key", "IDEMP-UAT-004")
                .contentType("application/json")
                .body(validBody(
                        "CONS-BULK-001",
                        "payroll.csv",
                        "instruction_id,payee_iban,amount\nINS-1,AE120001000000000000000001,10.00",
                        "wrong",
                        "PARTIAL_REJECTION"
                ))
                .when()
                .post("/open-finance/v1/file-payments")
                .then()
                .statusCode(400)
                .body("message", equalTo("Integrity Failure"));
    }

    private RequestSpecification request() {
        return given().baseUri("http://localhost").port(port)
                .contentType("application/json")
                .accept("application/json")
                .header("Authorization", "DPoP functional-token")
                .header("DPoP", "functional-proof")
                .header("X-FAPI-Interaction-ID", "ix-bulk-payments-functional")
                .header("x-fapi-financial-id", "TPP-001");
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
