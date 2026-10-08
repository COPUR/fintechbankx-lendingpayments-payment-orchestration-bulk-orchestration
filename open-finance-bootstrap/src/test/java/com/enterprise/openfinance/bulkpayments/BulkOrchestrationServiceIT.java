package com.enterprise.openfinance.bulkpayments;

import com.enterprise.openfinance.bulkpayments.domain.port.in.command.SubmitBulkFileCommand;
import com.enterprise.openfinance.bulkpayments.domain.exception.ConsentAlreadyUsedException;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkConsentContext;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkIntegrityMode;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkUploadResult;
import com.enterprise.openfinance.bulkpayments.domain.model.Money;
import com.enterprise.openfinance.bulkpayments.domain.port.in.BulkPaymentUseCase;
import com.enterprise.openfinance.bulkpayments.domain.port.in.ProcessBulkFilesUseCase;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkConsentPort;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkFilePort;
import com.enterprise.openfinance.bulkpayments.infrastructure.outbox.OutboxRelay;
import com.enterprise.openfinance.bulkpayments.infrastructure.outbox.SpringDataOutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * their events landing in the outbox and then on (a mocked) Kafka. The consent
 * port is stubbed: every consent id is a usable bulk consent of TPP-001, and a
 * consent authorises one file, so each upload helper call uses its own consent.
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
    private static final ECKey TPP_001_KEY = ecKey();
    private static final ECKey TPP_999_KEY = ecKey();

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
    @MockBean BulkConsentPort consents;

    /** Whether a database transaction was active at each consent read. */
    private final List<Boolean> transactionActiveAtConsentRead = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    void stubConsents() {
        transactionActiveAtConsentRead.clear();
        when(consents.findById(any())).thenAnswer(call -> {
            transactionActiveAtConsentRead.add(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive());
            return Optional.of(new BulkConsentContext(call.getArgument(0),
                "TPP-001", java.util.Set.of("INITIATEBULKPAYMENTS"), java.time.Instant.parse("2099-01-01T00:00:00Z"), true));
        });
    }

    @BeforeEach
    void stubTokens() {
        when(jwtDecoder.decode(any())).thenAnswer(call -> switch ((String) call.getArgument(0)) {
            case "tpp-001-token" -> token("TPP-001", "tpp-001-token", TPP_001_KEY);
            case "tpp-999-token" -> token("TPP-999", "tpp-999-token", TPP_999_KEY);
            default -> throw new BadJwtException("invalid token");
        });
    }

    /** DPoP-bound access token: cnf.jkt is the thumbprint of the TPP's proof key. */
    private static Jwt token(String tpp, String tokenValue, ECKey key) {
        return Jwt.withTokenValue(tokenValue).header("alg", "RS256")
                .subject("client-" + tpp).claim("azp", tpp)
                .claim("cnf", java.util.Map.of("jkt", thumbprint(key)))
                .audience(List.of("svc-pay-bulk-orchestration"))
                .issuedAt(java.time.Instant.now()).expiresAt(java.time.Instant.now().plusSeconds(300))
                .build();
    }

    @BeforeEach
    void cleanTables() {
        // As the schema owner: the runtime role may not delete files, items, bindings or idempotency records.
        JdbcTemplate owner = PostgresTestDatabase.owner();
        owner.update("delete from " + SCHEMA + ".dpop_proof_jti");
        owner.update("delete from " + SCHEMA + ".outbox_event");
        owner.update("delete from " + SCHEMA + ".bulk_idempotency");
        owner.update("delete from " + SCHEMA + ".bulk_consent_binding");
        owner.update("delete from " + SCHEMA + ".bulk_item");
        owner.update("delete from " + SCHEMA + ".bulk_file");
    }

    @Test
    void flywayCreatesOnlyTheTablesThisServiceOwns() {
        List<String> tables = jdbc.queryForList("""
                select table_name from information_schema.tables
                where table_schema = 'sc_pay_bulk_orchestration' and table_name <> 'flyway_schema_history'
                order by table_name
                """, String.class);

        assertThat(tables).containsExactly("bulk_consent_binding", "bulk_file", "bulk_idempotency", "bulk_item",
                "dpop_proof_jti", "outbox_event");
    }

    /**
     * The service connects as a runtime role with DML only: it cannot run DDL
     * (it owns neither the schema nor the tables), cannot delete or rewrite
     * what the code only appends, and cannot read Flyway's history. Tables a
     * later migration creates get DML by default (default privileges).
     */
    @Test
    void theRuntimeRoleCannotRunDdlOrDeleteFilesItemsOrBindings() {
        assertThat(jdbc.queryForObject("select current_user", String.class)).isEqualTo(PostgresTestDatabase.RUNTIME_ROLE);
        JdbcTemplate runtime = PostgresTestDatabase.runtime();

        assertThatThrownBy(() -> runtime.execute("create table " + SCHEMA + ".shadow (id int)"))
                .rootCause().hasMessageContaining("permission denied for schema " + SCHEMA);
        assertThatThrownBy(() -> runtime.execute("alter table " + SCHEMA + ".bulk_file add column shadow int"))
                .rootCause().hasMessageContaining("must be owner of").hasMessageContaining("bulk_file");
        assertThatThrownBy(() -> runtime.execute("drop table " + SCHEMA + ".outbox_event"))
                .rootCause().hasMessageContaining("must be owner of").hasMessageContaining("outbox_event");
        assertThatThrownBy(() -> runtime.execute("create index ix_shadow on " + SCHEMA + ".bulk_item (amount)"))
                .rootCause().hasMessageContaining("must be owner of").hasMessageContaining("bulk_item");
        assertThatThrownBy(() -> runtime.execute("truncate " + SCHEMA + ".bulk_file"))
                .rootCause().hasMessageContaining("permission denied for table bulk_file");
        for (String appendOnly : List.of("bulk_file", "bulk_item", "bulk_consent_binding", "bulk_idempotency")) {
            assertThatThrownBy(() -> runtime.update("delete from " + SCHEMA + "." + appendOnly))
                    .rootCause().hasMessageContaining("permission denied for table " + appendOnly);
        }
        assertThatThrownBy(() -> runtime.update("update " + SCHEMA + ".bulk_consent_binding set item_count = 0"))
                .rootCause().hasMessageContaining("permission denied for table bulk_consent_binding");
        assertThatThrownBy(() -> runtime.update("update " + SCHEMA + ".bulk_idempotency set file_status = 'REJECTED'"))
                .rootCause().hasMessageContaining("permission denied for table bulk_idempotency");
        assertThatThrownBy(() -> runtime.queryForObject("select count(*) from " + SCHEMA + ".flyway_schema_history", Integer.class))
                .rootCause().hasMessageContaining("permission denied for table flyway_schema_history");
        assertThat(runtime.queryForObject("select count(*) from " + SCHEMA + ".bulk_file", Integer.class)).isZero();

        JdbcTemplate owner = PostgresTestDatabase.owner();
        try {
            owner.execute("create table " + SCHEMA + ".later_table (id int)");
            assertThat(runtime.update("insert into " + SCHEMA + ".later_table values (1)")).isEqualTo(1);
            assertThat(runtime.update("delete from " + SCHEMA + ".later_table")).isEqualTo(1);
            assertThatThrownBy(() -> runtime.execute("truncate " + SCHEMA + ".later_table"))
                    .rootCause().hasMessageContaining("permission denied for table later_table");
        } finally {
            owner.execute("drop table if exists " + SCHEMA + ".later_table");
        }
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
                .andExpect(jsonPath("$.Data.Status").value("Validated"))
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
        // Validated, not completed: no item has reached initiation-settlement, so only Accepted is published.
        assertThat(eventTypes).containsExactly("Payments.BulkFile.Accepted.v1");
        List<String> correlation = jdbc.queryForList(
                "select correlation_id from " + SCHEMA + ".outbox_event order by created_seq", String.class);
        assertThat(correlation).containsExactly("ix-bulk-it");
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
    void currencyIsRequiredAndAmountsFollowItsMinorUnits() throws Exception {
        String content = csv("INS-1," + IBAN + ",10.5");
        String[][] refused = {
                {null, "currency is required"},
                {" ", "currency is required"},
                {"XAU", "Unsupported Currency"},
                {"aed", "Unsupported Currency"},
                {"JPY", "Schema Validation Failed"}};
        int key = 0;
        for (String[] row : refused) {
            mvc.perform(asTpp(post("/open-finance/v1/file-payments"))
                            .header("x-idempotency-key", "IDEMP-CCY-" + key++)
                            .contentType("application/json")
                            .content(body("CONS-BULK-001", "fx.csv", content, sha256(content), row[0],
                                    "PARTIAL_REJECTION")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(row[1]));
        }
        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".bulk_file", Integer.class)).isZero();

        String kwd = csv("INS-1," + IBAN + ",1.234", "INS-2," + IBAN + ",0.5");
        MvcResult accepted = mvc.perform(asTpp(post("/open-finance/v1/file-payments"))
                        .header("x-idempotency-key", "IDEMP-CCY-KWD")
                        .contentType("application/json")
                        .content(body("CONS-BULK-001", "kwd.csv", kwd, sha256(kwd), "KWD", "PARTIAL_REJECTION")))
                .andExpect(status().isAccepted())
                .andReturn();
        String fileId = json.readTree(accepted.getResponse().getContentAsString()).at("/Data/FilePaymentId").asText();
        while (processor.processNextBatch() > 0) {
            // drain
        }

        mvc.perform(asTpp(get("/open-finance/v1/file-payments/{id}/report", fileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Data.Items[0].Amount").value("1.234"))
                .andExpect(jsonPath("$.Data.Items[0].Currency").value("KWD"))
                .andExpect(jsonPath("$.Data.Items[1].Amount").value("0.500"));
        assertThat(jdbc.queryForObject("select currency from " + SCHEMA + ".bulk_file where file_id = ?",
                String.class, fileId)).isEqualTo("KWD");
        JsonNode event = json.readTree(jdbc.queryForObject("select payload from " + SCHEMA + ".outbox_event"
                + " where aggregate_id = ? and event_type = 'Payments.BulkFile.Accepted.v1'", String.class, fileId));
        assertThat(event.at("/data/totalAmount").asText()).isEqualTo("1.734");
        assertThat(event.at("/data/currency").asText()).isEqualTo("KWD");
    }

    @Test
    void storedTotalEventTotalAndReportShowTheSameFiguresWithoutRounding() throws Exception {
        String fileId = upload("IDEMP-FIGURES", csv("INS-1," + IBAN + ",0.10", "INS-2," + IBAN + ",0.20",
                "INS-3," + IBAN + ",999999999999.99"), "PARTIAL_REJECTION");
        while (processor.processNextBatch() > 0) {
            // drain
        }

        java.math.BigDecimal stored = jdbc.queryForObject("select total_amount from " + SCHEMA
                + ".bulk_file where file_id = ?", java.math.BigDecimal.class, fileId);
        JsonNode event = json.readTree(jdbc.queryForObject("select payload from " + SCHEMA + ".outbox_event"
                + " where aggregate_id = ? and event_type = 'Payments.BulkFile.Accepted.v1'", String.class, fileId));
        assertThat(event.at("/data/totalAmount").asText()).isEqualTo("1000000000000.29");
        assertThat(stored).isEqualByComparingTo(event.at("/data/totalAmount").asText());

        MvcResult report = mvc.perform(asTpp(get("/open-finance/v1/file-payments/{id}/report", fileId)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = json.readTree(report.getResponse().getContentAsString()).at("/Data/Items");
        java.math.BigDecimal reportSum = java.math.BigDecimal.ZERO;
        for (JsonNode item : items) {
            reportSum = reportSum.add(new java.math.BigDecimal(item.get("Amount").asText()));
        }
        assertThat(items.get(0).get("Amount").asText()).isEqualTo("0.10");
        assertThat(items.get(2).get("Amount").asText()).isEqualTo("999999999999.99");
        assertThat(reportSum).isEqualByComparingTo(stored);
        assertThat(jdbc.queryForList("select amount from " + SCHEMA + ".bulk_item where file_id = ?"
                + " order by line_number", java.math.BigDecimal.class, fileId))
                .usingElementComparator(java.math.BigDecimal::compareTo)
                .containsExactly(new java.math.BigDecimal("0.10"), new java.math.BigDecimal("0.20"),
                        new java.math.BigDecimal("999999999999.99"));
    }

    @Test
    void amountsTooLargeToStoreAreASchemaErrorNotAServerError() throws Exception {
        String content = csv("INS-1," + IBAN + ",1000000000000000.00");
        mvc.perform(asTpp(post("/open-finance/v1/file-payments"))
                        .header("x-idempotency-key", "IDEMP-HUGE")
                        .contentType("application/json")
                        .content(body("CONS-HUGE", "huge.csv", content, sha256(content), "PARTIAL_REJECTION")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Schema Validation Failed"));
        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".bulk_file", Integer.class)).isZero();
    }

    @Test
    void noDatabaseTransactionIsOpenWhileTheConsentServiceIsCalledAtUpload() throws Exception {
        upload("IDEMP-NO-TX", csv("INS-1," + IBAN + ",10.00"), "PARTIAL_REJECTION");

        assertThat(transactionActiveAtConsentRead).containsExactly(false);
    }

    @Test
    void anIdempotencyKeyIsNeverReusableEvenAfterItsRecordExpired() throws Exception {
        String content = csv("INS-1," + IBAN + ",10.00");
        String body = body("CONS-REUSE", "payroll.csv", content, sha256(content), "PARTIAL_REJECTION");
        String fileId = json.readTree(mvc.perform(asTpp(post("/open-finance/v1/file-payments"))
                        .header("x-idempotency-key", "IDEMP-REUSE").contentType("application/json").content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString()).at("/Data/FilePaymentId").asText();
        PostgresTestDatabase.owner().update("update " + SCHEMA + ".bulk_idempotency set created_at = now() - interval '3 days',"
                + " expires_at = now() - interval '2 days' where idempotency_key = 'IDEMP-REUSE'");

        // Same request after expiry: still the original file, never a second one (and never a 500).
        mvc.perform(asTpp(post("/open-finance/v1/file-payments"))
                        .header("x-idempotency-key", "IDEMP-REUSE").contentType("application/json").content(body))
                .andExpect(status().isAccepted())
                .andExpect(header().string("X-OF-Idempotency", "HIT"))
                .andExpect(jsonPath("$.Data.FilePaymentId").value(fileId));

        // Another file under the old key is a conflict.
        String other = csv("INS-1," + IBAN + ",20.00");
        mvc.perform(asTpp(post("/open-finance/v1/file-payments"))
                        .header("x-idempotency-key", "IDEMP-REUSE").contentType("application/json")
                        .content(body("CONS-REUSE-2", "other.csv", other, sha256(other), "PARTIAL_REJECTION")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".bulk_file", Integer.class)).isEqualTo(1);
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
                .andExpect(jsonPath("$.Data.Status").value("Validated"))
                .andExpect(jsonPath("$.Data.Items[1].ErrorMessage").value("Invalid IBAN"));
        mvc.perform(asTpp(get("/open-finance/v1/file-payments/{id}/report", fullId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Data.Status").value("Rejected"))
                .andExpect(jsonPath("$.Data.AcceptedCount").value(0));

        assertThat(jdbc.queryForList("select event_type from " + SCHEMA + ".outbox_event where aggregate_id = ?"
                + " order by created_seq", String.class, fullId))
                .containsExactly("Payments.BulkFile.Accepted.v1", "Payments.BulkFile.Rejected.v1");
        assertThat(jdbc.queryForList("select correlation_id from " + SCHEMA + ".outbox_event where aggregate_id = ?"
                + " order by created_seq", String.class, fullId)).containsExactly("ix-bulk-it", fullId);
        assertThat(jdbc.queryForList("select event_type from " + SCHEMA + ".outbox_event where aggregate_id = ?",
                String.class, partialId)).containsExactly("Payments.BulkFile.Accepted.v1");
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
        var done = files.findById(fileId).orElseThrow();
        assertThat(done.status().name()).isEqualTo("VALIDATED");
        assertThat(done.acceptedCount()).isEqualTo(1_200);
        assertThat(done.acceptedAmount()).isEqualTo(Money.of("1500.00", "AED"));
        assertThat(done.version()).isEqualTo(3L);
        // Items have not reached initiation-settlement, so no completion is published.
        assertThat(jdbc.queryForList("select event_type from " + SCHEMA + ".outbox_event where aggregate_id = ?",
                String.class, fileId)).containsExactly("Payments.BulkFile.Accepted.v1");
    }

    @Test
    void concurrentUploadsWithOneKeyCreateOneFile() throws Exception {
        String content = csv("INS-1," + IBAN + ",10.00");
        SubmitBulkFileCommand command = new SubmitBulkFileCommand("TPP-001", "CONS-BULK-001", "IDEMP-RACE",
                "payroll.csv", content, sha256(content), "AED", BulkIntegrityMode.PARTIAL_REJECTION, "ix-race");
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
    void aConsentAuthorisesOneFileAndTheBindingRecordsIt() throws Exception {
        String first = csv("INS-1," + IBAN + ",10.00", "INS-2,AE000,2.50");
        String fileId = json.readTree(mvc.perform(asTpp(post("/open-finance/v1/file-payments"))
                        .header("x-idempotency-key", "IDEMP-BIND-1")
                        .contentType("application/json")
                        .content(body("CONS-ONCE", "first.csv", first, sha256(first), "PARTIAL_REJECTION")))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString()).at("/Data/FilePaymentId").asText();

        String second = csv("INS-9," + IBAN + ",99.00");
        mvc.perform(asTpp(post("/open-finance/v1/file-payments"))
                        .header("x-idempotency-key", "IDEMP-BIND-2")
                        .contentType("application/json")
                        .content(body("CONS-ONCE", "second.csv", second, sha256(second), "PARTIAL_REJECTION")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONSENT_ALREADY_USED"))
                .andExpect(jsonPath("$.message").value("Consent already used for another file"));

        // The replay of the first upload is still answered.
        mvc.perform(asTpp(post("/open-finance/v1/file-payments"))
                        .header("x-idempotency-key", "IDEMP-BIND-1")
                        .contentType("application/json")
                        .content(body("CONS-ONCE", "first.csv", first, sha256(first), "PARTIAL_REJECTION")))
                .andExpect(status().isAccepted())
                .andExpect(header().string("X-OF-Idempotency", "HIT"));

        var binding = jdbc.queryForMap("select * from " + SCHEMA + ".bulk_consent_binding where consent_id = ?",
                "CONS-ONCE");
        assertThat(binding.get("file_id")).isEqualTo(fileId);
        assertThat(binding.get("tpp_id")).isEqualTo("TPP-001");
        assertThat(binding.get("file_hash")).isEqualTo(sha256(first));
        assertThat(binding.get("item_count")).isEqualTo(2);
        assertThat((java.math.BigDecimal) binding.get("control_sum")).isEqualByComparingTo("12.50");
        assertThat(binding.get("currency")).isEqualTo("AED");
        assertThat(binding.get("bound_at")).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".bulk_file", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".bulk_idempotency", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentFilesOnOneConsentBindOnlyOne() throws Exception {
        int uploads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(uploads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<BulkUploadResult>> results = new ArrayList<>();
        try {
            for (int i = 0; i < uploads; i++) {
                String content = csv("INS-" + i + "," + IBAN + "," + (i + 1) + ".00");
                SubmitBulkFileCommand command = new SubmitBulkFileCommand("TPP-001", "CONS-RACE", "IDEMP-CRACE-" + i,
                        "file-" + i + ".csv", content, sha256(content), "AED", BulkIntegrityMode.PARTIAL_REJECTION,
                        "ix-crace");
                results.add(pool.submit(() -> {
                    start.await();
                    return bulkPayments.submitFile(command);
                }));
            }
            start.countDown();
            int bound = 0;
            int refused = 0;
            for (Future<BulkUploadResult> result : results) {
                try {
                    result.get(30, TimeUnit.SECONDS);
                    bound++;
                } catch (java.util.concurrent.ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(ConsentAlreadyUsedException.class);
                    refused++;
                }
            }
            assertThat(bound).isEqualTo(1);
            assertThat(refused).isEqualTo(uploads - 1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".bulk_file", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".bulk_consent_binding", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".bulk_idempotency", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".outbox_event", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select b.file_id = f.file_id from " + SCHEMA + ".bulk_consent_binding b join "
                + SCHEMA + ".bulk_file f on f.consent_id = b.consent_id", Boolean.class)).isTrue();
    }

    @Test
    void aFileStopsWhenItsConsentIsRevokedBetweenBatches() throws Exception {
        List<String> rows = new ArrayList<>();
        for (int line = 1; line <= 700; line++) {
            rows.add("INS-" + line + "," + IBAN + ",1.00");
        }
        String fileId = upload("IDEMP-REVOKED", csv(rows.toArray(String[]::new)), "PARTIAL_REJECTION");
        assertThat(processor.processNextBatch()).isEqualTo(500);

        when(consents.findById("CONS-IDEMP-REVOKED")).thenReturn(Optional.of(new BulkConsentContext(
                "CONS-IDEMP-REVOKED", "TPP-001", java.util.Set.of("INITIATEBULKPAYMENTS"),
                java.time.Instant.parse("2099-01-01T00:00:00Z"), false)));
        assertThat(processor.processNextBatch()).isZero();

        mvc.perform(asTpp(get("/open-finance/v1/file-payments/{id}", fileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Data.Status").value("Stopped"));
        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".bulk_item where file_id = ?"
                + " and processed_at is null", Integer.class, fileId)).isEqualTo(200);
        JsonNode rejected = json.readTree(jdbc.queryForObject("select payload from " + SCHEMA + ".outbox_event"
                + " where aggregate_id = ? and event_type = 'Payments.BulkFile.Rejected.v1'", String.class, fileId));
        assertThat(rejected.at("/data/reason").asText()).isEqualTo("CONSENT_NOT_USABLE");
        assertThat(rejected.at("/data/rejectedCount").asInt()).isEqualTo(700);
        assertThat(processor.processNextBatch()).isZero();
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
    void relayNeverMarksOrParksOnOutagesButParksPayloadErrorsWithAReason() throws Exception {
        String fileId = upload("IDEMP-PARK", csv("INS-1," + IBAN + ",10.00"), "PARTIAL_REJECTION");
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(
                new org.apache.kafka.common.errors.TimeoutException("Expiring 1 record(s)")));
        for (int run = 0; run < 3; run++) {
            assertThat(newRelay().relayOnce()).isZero();
        }
        var pending = jdbc.queryForMap("select status, attempts, last_error, parked_reason from " + SCHEMA
                + ".outbox_event where aggregate_id = ?", fileId);
        assertThat(pending.get("status")).isEqualTo("PENDING");
        assertThat(pending.get("attempts")).isEqualTo(0);
        assertThat(pending.get("last_error")).isNull();

        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(
                new org.apache.kafka.common.errors.RecordTooLargeException("too large")));
        assertThat(newRelay().relayOnce()).isZero();
        var parked = jdbc.queryForMap("select status, parked_at, last_error, parked_reason from " + SCHEMA
                + ".outbox_event where aggregate_id = ?", fileId);
        assertThat(parked.get("status")).isEqualTo("PARKED");
        assertThat(parked.get("parked_at")).isNotNull();
        assertThat(parked.get("last_error")).isEqualTo("RecordTooLargeException");
        assertThat(parked.get("parked_reason")).isEqualTo("PAYLOAD_ERROR");
        assertThat(jdbc.queryForObject("select park_counted from " + SCHEMA + ".outbox_event where aggregate_id = ?",
                Boolean.class, fileId)).as("counted when the relay parked it").isTrue();

        // The database refuses a park without a reason (manual parks must record one).
        String eventId = jdbc.queryForObject("select event_id::text from " + SCHEMA + ".outbox_event"
                + " where aggregate_id = ?", String.class, fileId);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("update " + SCHEMA + ".outbox_event"
                        + " set parked_reason = null where event_id = ?::uuid", eventId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    /** Runbook operator park: counted once in outbox_parked_events_total{exception="OperatorPark"}. */
    @Test
    void anOperatorParkIsCountedOnceByTheRelay() throws Exception {
        String fileId = upload("IDEMP-OPPARK", csv("INS-1," + IBAN + ",10.00"), "PARTIAL_REJECTION");
        PostgresTestDatabase.owner().update("update " + SCHEMA + ".outbox_event set status = 'PARKED', parked_at = now(),"
                + " parked_reason = 'INC-1: topic ACL missing' where aggregate_id = ? and status = 'PENDING'", fileId);
        io.micrometer.core.instrument.simple.SimpleMeterRegistry meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        OutboxRelay relay = new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager), Clock.systemUTC(),
                100, Duration.ofSeconds(5), Duration.ofDays(7), meters);

        assertThat(relay.relayOnce()).isZero();
        assertThat(relay.relayOnce()).isZero();

        assertThat(meters.get("outbox.parked.events").tag("exception", "OperatorPark").counter().count())
                .as("counted once, not on every run").isEqualTo(1.0);
        assertThat(jdbc.queryForObject("select park_counted from " + SCHEMA + ".outbox_event where aggregate_id = ?",
                Boolean.class, fileId)).isTrue();
    }

    private OutboxRelay newRelay() {
        return new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager), Clock.systemUTC(), 100,
                Duration.ofSeconds(5), Duration.ofDays(7), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aParkedEventKeepsTheRestOfItsFileBlockedButNotOtherFiles() throws Exception {
        String blocked = upload("IDEMP-BLOCK-1", csv("INS-1,AE000,10.00"), "FULL_REJECTION");
        processor.processNextBatch(); // Accepted then Rejected for the same file
        String other = upload("IDEMP-BLOCK-2", csv("INS-1," + IBAN + ",10.00"), "PARTIAL_REJECTION");
        OutboxRelay relay = new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager),
                Clock.systemUTC(), 100, Duration.ofSeconds(5), Duration.ofDays(7),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new org.apache.kafka.common.errors.RecordTooLargeException("too large")))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(relay.relayOnce()).isZero();

        assertThat(jdbc.queryForList("select status from " + SCHEMA + ".outbox_event where aggregate_id = ?"
                + " order by created_seq", String.class, blocked)).containsExactly("PARKED", "PENDING");
        assertThat(jdbc.queryForList("select status from " + SCHEMA + ".outbox_event where aggregate_id = ?",
                String.class, other)).containsExactly("PUBLISHED");
        org.mockito.Mockito.verify(kafka, org.mockito.Mockito.times(2)).send(any(ProducerRecord.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void relayPublishesTheOutboxToKafkaInOrder() throws Exception {
        String fileId = upload("IDEMP-RELAY", csv("INS-1,AE000,10.00"), "PARTIAL_REJECTION");
        processor.processNextBatch();
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        OutboxRelay relay = new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager),
                Clock.systemUTC(), 100, Duration.ofSeconds(5), Duration.ofDays(7),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        assertThat(relay.relayOnce()).isEqualTo(2);

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        org.mockito.Mockito.verify(kafka, org.mockito.Mockito.times(2)).send(sent.capture());
        assertThat(sent.getAllValues()).extracting(ProducerRecord::topic)
                .containsExactly("evt.pay.bulk.accepted.v1", "evt.pay.bulk.rejected.v1");
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

        mvc.perform(asTpp(get("/open-finance/v1/file-payments/{id}", fileId), "tpp-999-token", TPP_999_KEY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mvc.perform(asTpp(get("/open-finance/v1/file-payments/{id}", fileId)).header("x-fapi-financial-id", "TPP-999"))
                .andExpect(status().isForbidden());

        mvc.perform(asTpp(get("/internal/anything")))
                .andExpect(status().isForbidden());
    }

    /** Error dispatches (sendError) must keep their status instead of being turned into 401/403. */
    @Test
    void malformedRequestsKeepTheirClientErrorStatus() throws Exception {
        mvc.perform(asTpp(post("/open-finance/v1/file-payments"))
                        .header("x-idempotency-key", "IDEMP-BAD-JSON")
                        .contentType("application/json")
                        .content("{\"Data\": {\"ConsentId\": "))
                .andExpect(status().isBadRequest());

        mvc.perform(asTpp(post("/open-finance/v1/file-payments"))
                        .header("x-idempotency-key", "IDEMP-BAD-TYPE")
                        .contentType("text/plain")
                        .content("hello"))
                .andExpect(status().isUnsupportedMediaType());

        mvc.perform(asTpp(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/open-finance/v1/file-payments/FILE-X")))
                .andExpect(status().isMethodNotAllowed());
    }

    /** A container error dispatch to /error (sendError from any layer) is not re-secured into 401/403. */
    @Test
    void errorDispatchKeepsTheOriginalStatus() throws Exception {
        mvc.perform(get("/error").with(request -> {
                    request.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);
                    request.setAttribute(jakarta.servlet.RequestDispatcher.ERROR_STATUS_CODE, 400);
                    request.setAttribute(jakarta.servlet.RequestDispatcher.ERROR_REQUEST_URI, "/open-finance/v1/file-payments");
                    return request;
                }))
                .andExpect(status().isBadRequest());
    }

    /**
     * The ingress gateway overwrites X-Forwarded-Proto/Host/Port; with
     * server.forward-headers-strategy=framework htu is the public URL the TPP called.
     */
    @Test
    void htuIsTheGatewayForwardedPublicUrl() throws Exception {
        String fileId = upload("IDEMP-HTU", csv("INS-1," + IBAN + ",10.00"), "PARTIAL_REJECTION");
        String path = "/open-finance/v1/file-payments/" + fileId;

        String publicProof = proof(TPP_001_KEY, "GET", "https://api.fintechbankx.example:8443" + path,
                "tpp-001-token", java.util.UUID.randomUUID().toString());
        mvc.perform(forwarded(get(path)).header("Authorization", "DPoP tpp-001-token").header("DPoP", publicProof))
                .andExpect(status().isOk());

        String internalProof = proof(TPP_001_KEY, "GET", "http://localhost" + path,
                "tpp-001-token", java.util.UUID.randomUUID().toString());
        mvc.perform(forwarded(get(path)).header("Authorization", "DPoP tpp-001-token").header("DPoP", internalProof))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_DPOP_PROOF"));
    }

    private static MockHttpServletRequestBuilder forwarded(MockHttpServletRequestBuilder builder) {
        return builder.header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "api.fintechbankx.example")
                .header("X-Forwarded-Port", "8443")
                .header("X-Forwarded-For", "203.0.113.7")
                .header("X-FAPI-Interaction-ID", "ix-htu");
    }

    @Test
    void dpopProofIsRequiredAndVerifiedOnTheTppApi() throws Exception {
        String fileId = upload("IDEMP-DPOP", csv("INS-1," + IBAN + ",10.00"), "PARTIAL_REJECTION");
        String path = "/open-finance/v1/file-payments/" + fileId;

        mvc.perform(get(path).header("Authorization", "DPoP tpp-001-token").header("X-FAPI-Interaction-ID", "ix-d1"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("DPoP error=\"invalid_dpop_proof\"")))
                .andExpect(jsonPath("$.code").value("INVALID_DPOP_PROOF"));

        mvc.perform(get(path).header("Authorization", "Bearer tpp-001-token")
                        .header("X-FAPI-Interaction-ID", "ix-d2").with(dpop("tpp-001-token", TPP_001_KEY)))
                .andExpect(status().isUnauthorized());

        mvc.perform(asTpp(get(path), "tpp-001-token", TPP_999_KEY))
                .andExpect(status().isUnauthorized());

        String otherUrlProof = proof(TPP_001_KEY, "GET", "http://localhost/open-finance/v1/file-payments/OTHER",
                "tpp-001-token", java.util.UUID.randomUUID().toString());
        mvc.perform(get(path).header("Authorization", "DPoP tpp-001-token").header("DPoP", otherUrlProof)
                        .header("X-FAPI-Interaction-ID", "ix-d3"))
                .andExpect(status().isUnauthorized());

        String once = proof(TPP_001_KEY, "GET", "http://localhost" + path, "tpp-001-token", "jti-it-replay");
        mvc.perform(get(path).header("Authorization", "DPoP tpp-001-token").header("DPoP", once)
                        .header("X-FAPI-Interaction-ID", "ix-d4"))
                .andExpect(status().isOk());
        mvc.perform(get(path).header("Authorization", "DPoP tpp-001-token").header("DPoP", once)
                        .header("X-FAPI-Interaction-ID", "ix-d5"))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".dpop_proof_jti", Integer.class))
                .isGreaterThanOrEqualTo(2);
    }

    private String upload(String idempotencyKey, String content, String mode) throws Exception {
        MvcResult result = mvc.perform(asTpp(post("/open-finance/v1/file-payments"))
                        .header("x-idempotency-key", idempotencyKey)
                        .contentType("application/json")
                        .content(body("CONS-" + idempotencyKey, "file.csv", content, sha256(content), mode)))
                .andExpect(status().isAccepted())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).at("/Data/FilePaymentId").asText();
    }

    private static MockHttpServletRequestBuilder asTpp(MockHttpServletRequestBuilder builder) {
        return asTpp(builder, "tpp-001-token", TPP_001_KEY);
    }

    private static MockHttpServletRequestBuilder asTpp(MockHttpServletRequestBuilder builder, String token, ECKey key) {
        return builder
                .header("Authorization", "DPoP " + token)
                .header("X-FAPI-Interaction-ID", "ix-bulk-it")
                .accept("application/json")
                .with(dpop(token, key));
    }

    /** Adds a fresh RFC 9449 proof for the request's method and URL, bound to the access token. */
    private static RequestPostProcessor dpop(String token, ECKey key) {
        return request -> {
            request.addHeader("DPoP", proof(key, request.getMethod(), request.getRequestURL().toString(), token,
                    java.util.UUID.randomUUID().toString()));
            return request;
        };
    }

    private static String proof(ECKey key, String method, String url, String token, String jti) {
        try {
            byte[] ath = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.ES256).type(new JOSEObjectType("dpop+jwt"))
                            .jwk(key.toPublicJWK()).build(),
                    new JWTClaimsSet.Builder().claim("htm", method).claim("htu", url)
                            .claim("ath", Base64.getUrlEncoder().withoutPadding().encodeToString(ath))
                            .issueTime(new java.util.Date()).jwtID(jti).build());
            jwt.sign(new ECDSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ECKey ecKey() {
        try {
            return new ECKeyGenerator(Curve.P_256).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String thumbprint(ECKey key) {
        try {
            return key.computeThumbprint("SHA-256").toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String body(String consentId, String fileName, String content, String hash, String mode) throws Exception {
        return body(consentId, fileName, content, hash, "AED", mode);
    }

    private String body(String consentId, String fileName, String content, String hash, String currency, String mode)
            throws Exception {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("ConsentId", consentId);
        data.put("FileName", fileName);
        data.put("FileContent", content);
        data.put("FileHash", hash);
        if (currency != null) {
            data.put("Currency", currency);
        }
        data.put("IntegrityMode", mode);
        return json.writeValueAsString(java.util.Map.of("Data", data));
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
