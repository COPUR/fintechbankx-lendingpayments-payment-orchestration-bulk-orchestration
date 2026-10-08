package com.enterprise.openfinance.bulkpayments;

import com.enterprise.openfinance.bulkpayments.domain.port.in.command.SubmitBulkFileCommand;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkIntegrityMode;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkUploadResult;
import com.enterprise.openfinance.bulkpayments.domain.port.in.BulkPaymentUseCase;
import com.enterprise.openfinance.bulkpayments.domain.port.in.ProcessBulkFilesUseCase;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkFilePort;
import com.enterprise.openfinance.bulkpayments.infrastructure.outbox.OutboxRelay;
import com.enterprise.openfinance.bulkpayments.infrastructure.outbox.SpringDataOutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the whole service against PostgreSQL: Flyway builds
 * sc_pay_bulk_orchestration, Hibernate validates the entities, and files go
 * through upload, bounded-batch processing and reporting over HTTP with
 * their events landing in the outbox and then on (a mocked) Kafka. Consents
 * come from the in-memory adapter (CONS-BULK-001 belongs to TPP-001).
 *
 * Scenarios ported from the seed's uncompiled integrationTest and
 * functionalTest source sets (BulkPaymentsApiIntegrationTest, BulkPaymentsUatTest).
 */
@SpringBootTest(properties = {
        "openfinance.bulkpayments.consent.adapter=in-memory",
        "openfinance.bulkpayments.processing.enabled=false",
        "openfinance.bulkpayments.processing.batch-size=500",
        "openfinance.bulkpayments.outbox.relay.enabled=false",
        "management.tracing.enabled=false"
})
@AutoConfigureMockMvc
class BulkOrchestrationServiceIT {

    private static final String SCHEMA = "sc_pay_bulk_orchestration";
    private static final String IBAN = "AE120001000000000000000001";

    @BeforeAll
    static void requireDatabase() {
        PostgresTestDatabase.assumeAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired BulkPaymentUseCase bulkPayments;
    @Autowired ProcessBulkFilesUseCase processor;
    @Autowired BulkFilePort files;
    @Autowired SpringDataOutboxRepository outbox;
    @Autowired PlatformTransactionManager transactionManager;
    @MockBean KafkaTemplate<String, String> kafka;
    /** Signature and aud checks are unit-tested in JwtValidationTest; here tokens map to fixed claims. */
    @MockBean JwtDecoder jwtDecoder;

    @BeforeEach
    void stubTokens() {
        when(jwtDecoder.decode(any())).thenAnswer(call -> switch ((String) call.getArgument(0)) {
            case "tpp-001-token" -> token("TPP-001");
            case "tpp-999-token" -> token("TPP-999");
            default -> throw new BadJwtException("invalid token");
        });
    }

    private static Jwt token(String tpp) {
        return Jwt.withTokenValue("tpp-token").header("alg", "RS256")
                .subject("client-" + tpp).claim("azp", tpp)
                .audience(List.of("svc-pay-bulk-orchestration"))
                .issuedAt(java.time.Instant.now()).expiresAt(java.time.Instant.now().plusSeconds(300))
                .build();
    }

    @BeforeEach
    void cleanTables() {
        jdbc.update("delete from " + SCHEMA + ".outbox_event");
        jdbc.update("delete from " + SCHEMA + ".bulk_idempotency");
        jdbc.update("delete from " + SCHEMA + ".bulk_item");
        jdbc.update("delete from " + SCHEMA + ".bulk_file");
    }

    @Test
    void flywayCreatesOnlyTheTablesThisServiceOwns() {
        List<String> tables = jdbc.queryForList("""
                select table_name from information_schema.tables
                where table_schema = 'sc_pay_bulk_orchestration' and table_name <> 'flyway_schema_history'
                order by table_name
                """, String.class);

        assertThat(tables).containsExactly("bulk_file", "bulk_idempotency", "bulk_item", "outbox_event");
    }

    @Test
    void uploadProcessAndReportOverHttpWithIdempotencyAndEtags() throws Exception {
        String content = csv("INS-1," + IBAN + ",10.00");
        String body = body("CONS-BULK-001", "payroll.csv", content, sha256(content), "PARTIAL_REJECTION");

        MvcResult first = mvc.perform(asTpp(post("/open-finance/v1/file-payments"))
                        .header("x-idempotency-key", "IDEMP-INT-001")
                        .contentType("application/json").content(body))
                .andExpect(status().isAccepted())
                .andExpect(header().string("X-OF-Idempotency", "MISS"))
                .andExpect(jsonPath("$.Data.Status").value("Processing"))
                .andReturn();
        String fileId = json.readTree(first.getResponse().getContentAsString()).at("/Data/FilePaymentId").asText();

        mvc.perform(asTpp(post("/open-finance/v1/file-payments"))
                        .header("x-idempotency-key", "IDEMP-INT-001")
                        .contentType("application/json").content(body))
                .andExpect(status().isAccepted())
                .andExpect(header().string("X-OF-Idempotency", "HIT"))
                .andExpect(jsonPath("$.Data.FilePaymentId").value(fileId));

        mvc.perform(asTpp(get("/open-finance/v1/file-payments/{id}", fileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Data.Status").value("Processing"));

        assertThat(processor.processNextBatch()).isEqualTo(1);
        assertThat(processor.processNextBatch()).isZero();

        MvcResult statusResult = mvc.perform(asTpp(get("/open-finance/v1/file-payments/{id}", fileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Data.Status").value("Completed"))
                .andExpect(header().exists("ETag"))
                .andReturn();
        MvcResult reportResult = mvc.perform(asTpp(get("/open-finance/v1/file-payments/{id}/report", fileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Data.AcceptedCount").value(1))
                .andExpect(jsonPath("$.Data.Items[0].Amount").value("10.00"))
                .andReturn();

        mvc.perform(asTpp(get("/open-finance/v1/file-payments/{id}/report", fileId))
                        .header("If-None-Match", reportResult.getResponse().getHeader("ETag")))
                .andExpect(status().isNotModified());
        mvc.perform(asTpp(get("/open-finance/v1/file-payments/{id}", fileId))
                        .header("If-None-Match", statusResult.getResponse().getHeader("ETag")))
                .andExpect(status().isNotModified());

        List<String> eventTypes = jdbc.queryForList(
                "select event_type from " + SCHEMA + ".outbox_event order by created_seq", String.class);
        assertThat(eventTypes).containsExactly("Payments.BulkFile.Accepted.v1", "Payments.BulkFile.Completed.v1");
        List<String> correlation = jdbc.queryForList(
                "select correlation_id from " + SCHEMA + ".outbox_event order by created_seq", String.class);
        assertThat(correlation).containsExactly("ix-bulk-it", fileId);
    }

    @Test
    void rejectsInvalidPayloadsWithoutWritingAnything() throws Exception {
        mvc.perform(asTpp(post("/open-finance/v1/file-payments"))
                        .header("x-idempotency-key", "IDEMP-INT-002")
                        .contentType("application/json")
                        .content(body("CONS-BULK-001", "empty.csv", "", "x", "PARTIAL_REJECTION")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));

        String badHeader = "bad_header\nINS-1," + IBAN + ",10.00";
        mvc.perform(asTpp(post("/open-finance/v1/file-payments"))
                        .header("x-idempotency-key", "IDEMP-INT-003")
                        .contentType("application/json")
                        .content(body("CONS-BULK-001", "bad.csv", badHeader, sha256(badHeader), "PARTIAL_REJECTION")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Schema Validation Failed"));

        String content = csv("INS-1," + IBAN + ",10.00");
        mvc.perform(asTpp(post("/open-finance/v1/file-payments"))
                        .header("x-idempotency-key", "IDEMP-INT-004")
                        .contentType("application/json")
                        .content(body("CONS-BULK-001", "payroll.csv", content, "wrong", "PARTIAL_REJECTION")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Integrity Failure"));

        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".bulk_file", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".outbox_event", Integer.class)).isZero();
    }

    @Test
    void partialAndFullRejectionModes() throws Exception {
        String mixed = csv("INS-1," + IBAN + ",10.00", "INS-2,AE000,10.00");

        String partialId = upload("IDEMP-INT-005", mixed, "PARTIAL_REJECTION");
        String fullId = upload("IDEMP-INT-006", mixed, "FULL_REJECTION");
        while (processor.processNextBatch() > 0) {
            // drain
        }

        mvc.perform(asTpp(get("/open-finance/v1/file-payments/{id}/report", partialId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Data.Status").value("PartiallyAccepted"))
                .andExpect(jsonPath("$.Data.Items[1].ErrorMessage").value("Invalid IBAN"));
        mvc.perform(asTpp(get("/open-finance/v1/file-payments/{id}/report", fullId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Data.Status").value("Rejected"))
                .andExpect(jsonPath("$.Data.AcceptedCount").value(0));

        assertThat(jdbc.queryForList("select event_type from " + SCHEMA + ".outbox_event where aggregate_id = ?"
                + " order by created_seq", String.class, fullId))
                .containsExactly("Payments.BulkFile.Accepted.v1", "Payments.BulkFile.Rejected.v1");
    }

    @Test
    void largeFileIsStoredItemByItemAndProcessedInBoundedBatches() throws Exception {
        List<String> rows = new ArrayList<>();
        for (int line = 1; line <= 1_201; line++) {
            rows.add("INS-" + line + "," + (line == 600 ? "AE000" : IBAN) + ",1.25");
        }
        String fileId = upload("IDEMP-INT-LARGE", csv(rows.toArray(String[]::new)), "PARTIAL_REJECTION");

        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".bulk_item where file_id = ?",
                Integer.class, fileId)).isEqualTo(1_201);
        assertThat(processor.processNextBatch()).isEqualTo(500);
        assertThat(processor.processNextBatch()).isEqualTo(500);
        assertThat(files.findById(fileId).orElseThrow().processedCount()).isEqualTo(1_000);
        assertThat(processor.processNextBatch()).isEqualTo(201);
        assertThat(processor.processNextBatch()).isZero();

        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".bulk_item where file_id = ?"
                + " and processed_at is null", Integer.class, fileId)).isZero();
        String payload = jdbc.queryForObject("select payload::text from " + SCHEMA + ".outbox_event"
                + " where event_type = 'Payments.BulkFile.Completed.v1'", String.class);
        JsonNode data = json.readTree(payload).get("data");
        assertThat(data.get("outcome").asText()).isEqualTo("PARTIALLY_ACCEPTED");
        assertThat(data.get("acceptedCount").asInt()).isEqualTo(1_200);
        assertThat(data.get("acceptedAmount").asText()).isEqualTo("1500.00");
        assertThat(json.readTree(payload).get("aggregateVersion").asLong()).isEqualTo(3L);
    }

    @Test
    void concurrentUploadsWithOneKeyCreateOneFile() throws Exception {
        String content = csv("INS-1," + IBAN + ",10.00");
        SubmitBulkFileCommand command = new SubmitBulkFileCommand("TPP-001", "CONS-BULK-001", "IDEMP-RACE",
                "payroll.csv", content, sha256(content), BulkIntegrityMode.PARTIAL_REJECTION, "ix-race");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<BulkUploadResult>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return bulkPayments.submitFile(command);
                }));
            }
            start.countDown();
            List<String> fileIds = new ArrayList<>();
            for (Future<BulkUploadResult> result : results) {
                fileIds.add(result.get(30, TimeUnit.SECONDS).fileId());
            }
            assertThat(fileIds).containsOnly(fileIds.get(0));
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".bulk_file", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".outbox_event", Integer.class)).isEqualTo(1);
    }

    @Test
    void twoProcessorsNeverClaimTheSameFile() throws Exception {
        String first = upload("IDEMP-CLAIM-1", csv("INS-1," + IBAN + ",1.00"), "PARTIAL_REJECTION");
        String second = upload("IDEMP-CLAIM-2", csv("INS-1," + IBAN + ",2.00"), "PARTIAL_REJECTION");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch firstClaimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<String>> holder = pool.submit(() -> tx.execute(status -> {
                Optional<String> claimed = files.claimNextProcessing().map(f -> f.fileId());
                firstClaimed.countDown();
                await(release);
                return claimed;
            }));
            assertThat(firstClaimed.await(10, TimeUnit.SECONDS)).isTrue();
            Optional<String> other = tx.execute(status -> files.claimNextProcessing().map(f -> f.fileId()));
            release.countDown();

            assertThat(holder.get(10, TimeUnit.SECONDS)).contains(first);
            assertThat(other).contains(second);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void relayPublishesTheOutboxToKafkaInOrder() throws Exception {
        String fileId = upload("IDEMP-RELAY", csv("INS-1," + IBAN + ",10.00"), "PARTIAL_REJECTION");
        processor.processNextBatch();
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        OutboxRelay relay = new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager),
                Clock.systemUTC(), 100, 10, Duration.ofSeconds(5), Duration.ofDays(7));

        assertThat(relay.relayOnce()).isEqualTo(2);

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        org.mockito.Mockito.verify(kafka, org.mockito.Mockito.times(2)).send(sent.capture());
        assertThat(sent.getAllValues()).extracting(ProducerRecord::topic)
                .containsExactly("evt.pay.bulk.file-accepted.v1", "evt.pay.bulk.file-completed.v1");
        assertThat(sent.getAllValues()).extracting(ProducerRecord::key).containsOnly(fileId);
        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".outbox_event where status = 'PENDING'",
                Integer.class)).isZero();
    }

    @Test
    void securityRefusesMissingTokensAndOtherTpps() throws Exception {
        String fileId = upload("IDEMP-SEC", csv("INS-1," + IBAN + ",10.00"), "PARTIAL_REJECTION");

        mvc.perform(get("/open-finance/v1/file-payments/{id}", fileId)
                        .header("Authorization", "DPoP not-a-token").header("DPoP", "proof")
                        .header("X-FAPI-Interaction-ID", "ix-1"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/open-finance/v1/file-payments/{id}", fileId)
                        .header("Authorization", "DPoP tpp-999-token").header("DPoP", "proof")
                        .header("X-FAPI-Interaction-ID", "ix-1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mvc.perform(asTpp(get("/open-finance/v1/file-payments/{id}", fileId)).header("x-fapi-financial-id", "TPP-999"))
                .andExpect(status().isForbidden());

        mvc.perform(asTpp(get("/internal/anything")))
                .andExpect(status().isForbidden());
    }

    private String upload(String idempotencyKey, String content, String mode) throws Exception {
        MvcResult result = mvc.perform(asTpp(post("/open-finance/v1/file-payments"))
                        .header("x-idempotency-key", idempotencyKey)
                        .contentType("application/json")
                        .content(body("CONS-BULK-001", "file.csv", content, sha256(content), mode)))
                .andExpect(status().isAccepted())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).at("/Data/FilePaymentId").asText();
    }

    private static MockHttpServletRequestBuilder asTpp(MockHttpServletRequestBuilder builder) {
        return builder
                .header("Authorization", "DPoP tpp-001-token")
                .header("DPoP", "proof-jwt")
                .header("X-FAPI-Interaction-ID", "ix-bulk-it")
                .accept("application/json");
    }

    private String body(String consentId, String fileName, String content, String hash, String mode) throws Exception {
        return json.writeValueAsString(java.util.Map.of("Data", java.util.Map.of(
                "ConsentId", consentId, "FileName", fileName, "FileContent", content,
                "FileHash", hash, "IntegrityMode", mode)));
    }

    private static String csv(String... rows) {
        return "instruction_id,payee_iban,amount\n" + String.join("\n", rows);
    }

    private static String sha256(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
