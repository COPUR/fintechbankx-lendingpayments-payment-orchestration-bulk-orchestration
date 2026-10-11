package com.enterprise.openfinance.bulkpayments.startup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Governance round 3, item 2, hardened in round 6 (guardrail 4a): the service fails fast unless every datasource
 * URL a pool can use (spring.datasource.url, spring.datasource.hikari.jdbc-url, spring.flyway.url) carries exactly
 * one lower-case sslmode=verify-full, read the way PgJDBC reads it, and, when a Kafka client is configured, the
 * producer's effective security.protocol (spring.kafka.properties and spring.kafka.producer.properties included)
 * is TLS. The message names the offending setting and never repeats the datasource URL (it may carry credentials).
 */
class TlsEnforcementInitializerTest {

    private static final String CA = "&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem";
    private static final String VERIFY_FULL =
            "jdbc:postgresql://aurora-writer:5432/db_pay_bulk_orchestration_prod?sslmode=verify-full" + CA;
    private static final String REQUIRE = "jdbc:postgresql://aurora-writer:5432/db_pay_bulk_orchestration_prod?sslmode=require";
    private static final String KAFKA_TLS = "spring.kafka.security.protocol=SASL_SSL";
    private static final String MSK = "spring.kafka.bootstrap-servers=b-1.msk:9098";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new TlsEnforcementInitializer());

    @Test
    void failsOnSslmodeRequire() {
        runner.withPropertyValues("spring.datasource.url=" + REQUIRE, MSK, KAFKA_TLS)
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("spring.datasource.url")
                            .hasMessageContaining("sslmode=verify-full")
                            .hasMessageContaining("found sslmode=require");
                    assertThat(failure.getMessage()).as("never repeats the URL").doesNotContain("jdbc:", "aurora-writer");
                });
    }

    /** PgJDBC takes the last sslmode: verify-full first and disable later connects with disable. */
    @Test
    void failsWhenSslmodeAppearsTwice() {
        runner.withPropertyValues("spring.datasource.url=" + VERIFY_FULL + "&sslmode=disable", MSK, KAFKA_TLS)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("spring.datasource.url")
                            .hasMessageContaining("sslmode 2 times")
                            .hasMessageContaining("found sslmode=disable");
                });
    }

    /** PgJDBC reads keys case-sensitively: SSLMODE is ignored and the driver falls back to prefer (no verification). */
    @Test
    void failsWhenTheOnlySslmodeIsUpperCase() {
        runner.withPropertyValues("spring.datasource.url=jdbc:postgresql://h:5432/db?SSLMODE=verify-full" + CA, MSK, KAFKA_TLS)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("spring.datasource.url")
                            .hasMessageContaining("SSLMODE")
                            .hasMessageContaining("case-sensitively")
                            .hasMessageContaining("found no sslmode");
                });
    }

    /** These keys bypass certificate or host name verification, or load TLS settings from pg_service.conf. */
    @ParameterizedTest
    @ValueSource(strings = {"sslfactory=org.postgresql.ssl.NonValidatingFactory", "sslfactoryarg=x", "sslhostnameverifier=x.Y",
            "sslpasswordcallback=x", "service=aurora"})
    void failsOnAVerificationBypassKey(String parameter) {
        runner.withPropertyValues("spring.datasource.url=" + VERIFY_FULL + "&" + parameter, MSK, KAFKA_TLS)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("spring.datasource.url")
                            .hasMessageContaining("must not set " + parameter.substring(0, parameter.indexOf('=')));
                });
    }

    /** A key is the text before the first '=': an sslmode hidden in another parameter's value is not an sslmode. */
    @Test
    void anSslmodeHiddenInAnotherValueDoesNotCount() {
        runner.withPropertyValues("spring.datasource.url=jdbc:postgresql://h:5432/db?sslmode=require&applicationName=sslmode=verify-full" + CA,
                        MSK, KAFKA_TLS)
                .run(context -> assertThat(context.getStartupFailure()).hasMessageContaining("found sslmode=require"));
    }

    /** spring.datasource.hikari.jdbc-url replaces spring.datasource.url for the pool; it is held to the same rule. */
    @Test
    void theHikariJdbcUrlIsCheckedToo() {
        runner.withPropertyValues("spring.datasource.url=" + VERIFY_FULL, "spring.datasource.hikari.jdbc-url=" + REQUIRE, MSK, KAFKA_TLS)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("spring.datasource.hikari.jdbc-url")
                            .hasMessageContaining("found sslmode=require");
                    assertThat(context.getStartupFailure().getMessage()).doesNotContain("aurora-writer");
                });
    }

    /** spring.flyway.url, when set, is the migration Job's own URL; it is held to the same rule. */
    @Test
    void theFlywayUrlIsCheckedToo() {
        runner.withPropertyValues("spring.datasource.url=" + VERIFY_FULL, "spring.flyway.url=" + REQUIRE, "spring.kafka.bootstrap-servers=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("spring.flyway.url")
                            .hasMessageContaining("found sslmode=require");
                });
    }

    @Test
    void failsOnPlaintextKafka() {
        runner.withPropertyValues("spring.datasource.url=" + VERIFY_FULL,
                        "spring.kafka.bootstrap-servers=localhost:9092", "spring.kafka.security.protocol=PLAINTEXT")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("spring.kafka.security.protocol")
                            .hasMessageContaining("SASL_SSL")
                            .hasMessageContaining("found PLAINTEXT");
                });
    }

    /** The producer's own properties override the common protocol; the client uses what the producer builds. */
    @Test
    void failsWhenTheProducerPropertiesOverrideTheProtocolToPlaintext() {
        runner.withPropertyValues("spring.datasource.url=" + VERIFY_FULL, MSK, KAFKA_TLS,
                        "spring.kafka.producer.properties.security.protocol=PLAINTEXT")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("spring.kafka.security.protocol")
                            .hasMessageContaining("found PLAINTEXT");
                });
    }

    /** spring.kafka.properties.* overrides spring.kafka.security.protocol as well. */
    @Test
    void failsWhenTheCommonPropertiesOverrideTheProtocolToPlaintext() {
        runner.withPropertyValues("spring.datasource.url=" + VERIFY_FULL, MSK, KAFKA_TLS,
                        "spring.kafka.properties.security.protocol=PLAINTEXT")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("found PLAINTEXT");
                });
    }

    @Test
    void passesOnVerifyFullPlusSaslSsl() {
        runner.withPropertyValues("spring.datasource.url=" + VERIFY_FULL, MSK, KAFKA_TLS)
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** The Strimzi profile (application-kafka-strimzi.yml) is mutual TLS: protocol SSL, no SASL. */
    @Test
    void passesOnStrimziMutualTls() {
        runner.withPropertyValues("spring.datasource.url=" + VERIFY_FULL,
                        "spring.kafka.bootstrap-servers=kafka-bootstrap.kafka:9093", "spring.kafka.security.protocol=SSL")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** A TLS protocol set only through the producer properties is as good as the common one. */
    @Test
    void passesWhenOnlyTheProducerPropertiesSetATlsProtocol() {
        runner.withPropertyValues("spring.datasource.url=" + VERIFY_FULL, MSK,
                        "spring.kafka.producer.properties.security.protocol=SASL_SSL")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void failsOnSaslPlaintextAndOnAMissingProtocol() {
        runner.withPropertyValues("spring.datasource.url=" + VERIFY_FULL,
                        "spring.kafka.bootstrap-servers=b-1:9096", "spring.kafka.security.protocol=SASL_PLAINTEXT")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("spring.datasource.url=" + VERIFY_FULL, "spring.kafka.bootstrap-servers=b-1:9096")
                .run(context -> assertThat(context.getStartupFailure()).hasMessageContaining("found none"));
    }

    @Test
    void failsOnADatasourceUrlWithoutAnySslmode() {
        runner.withPropertyValues("spring.datasource.url=jdbc:postgresql://localhost:5432/db_pay_bulk_orchestration_test")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasMessageContaining("spring.datasource.url").hasMessageContaining("found no sslmode"));
    }

    /** Both wrong: one failure names both settings, so an operator fixes them in one go. */
    @Test
    void namesEveryOffendingSettingAtOnce() {
        runner.withPropertyValues("spring.datasource.url=" + REQUIRE, MSK, "spring.kafka.security.protocol=PLAINTEXT")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasMessageContaining("spring.datasource.url").hasMessageContaining("spring.kafka.security.protocol"));
    }

    /** The migrate Job configures no Kafka client: a blank bootstrap-servers skips the Kafka check only. */
    @Test
    void withoutAKafkaClientOnlyTheDatasourceIsChecked() {
        runner.withPropertyValues("spring.datasource.url=" + VERIFY_FULL,
                        "spring.kafka.bootstrap-servers=", "spring.kafka.security.protocol=PLAINTEXT")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("spring.datasource.url=" + REQUIRE, "spring.kafka.bootstrap-servers=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void theSwitchOffIsExplicitAndPassesEverything() {
        runner.withPropertyValues("fintechbankx.tls.enforce=false", "spring.datasource.url=" + REQUIRE,
                        "spring.kafka.bootstrap-servers=localhost:9092", "spring.kafka.security.protocol=PLAINTEXT")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * The query is read the way PgJDBC reads it: keys case-sensitive (SSLMODE is not sslmode), a key is the text
     * before the first '=', every occurrence counts, and values are percent-decoded (verify%2Dfull is verify-full).
     * This replaces the round-3 test that pinned case-insensitive, first-match reading.
     */
    @Test
    void sslmodeIsReadTheWayPgJdbcReadsIt() {
        assertThat(TlsEnforcementInitializer.sslmodes("jdbc:postgresql://h/db?a=1&SSLMODE=Verify-Full&b=2")).isEmpty();
        assertThat(TlsEnforcementInitializer.sslmodes("jdbc:postgresql://h/db?sslmode=verify-full&x=1&sslmode=disable"))
                .containsExactly("verify-full", "disable");
        assertThat(TlsEnforcementInitializer.sslmodes("jdbc:postgresql://h/db?sslmode=require&applicationName=sslmode=verify-full"))
                .containsExactly("require");
        assertThat(TlsEnforcementInitializer.sslmodes("jdbc:postgresql://h/db?sslmode=verify%2Dfull")).containsExactly("verify-full");
        assertThat(TlsEnforcementInitializer.sslmodes("jdbc:postgresql://h/db")).isEmpty();
    }
}
