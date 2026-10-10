package com.enterprise.openfinance.bulkpayments.startup;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Governance round 3, item 2: the service fails fast unless the datasource URL has sslmode=verify-full and,
 * when a Kafka client is configured, the Kafka security protocol is TLS. The message names the offending
 * setting and never repeats the datasource URL (it may carry credentials).
 */
class TlsEnforcementInitializerTest {

    private static final String VERIFY_FULL =
            "jdbc:postgresql://aurora-writer:5432/db_pay_bulk_orchestration_prod?sslmode=verify-full"
                    + "&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem";
    private static final String REQUIRE = "jdbc:postgresql://aurora-writer:5432/db_pay_bulk_orchestration_prod?sslmode=require";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new TlsEnforcementInitializer());

    @Test
    void failsOnSslmodeRequire() {
        runner.withPropertyValues("spring.datasource.url=" + REQUIRE,
                        "spring.kafka.bootstrap-servers=b-1.msk:9098", "spring.kafka.security.protocol=SASL_SSL")
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

    @Test
    void passesOnVerifyFullPlusSaslSsl() {
        runner.withPropertyValues("spring.datasource.url=" + VERIFY_FULL,
                        "spring.kafka.bootstrap-servers=b-1.msk:9098", "spring.kafka.security.protocol=SASL_SSL")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** The Strimzi profile (application-kafka-strimzi.yml) is mutual TLS: protocol SSL, no SASL. */
    @Test
    void passesOnStrimziMutualTls() {
        runner.withPropertyValues("spring.datasource.url=" + VERIFY_FULL,
                        "spring.kafka.bootstrap-servers=kafka-bootstrap.kafka:9093", "spring.kafka.security.protocol=SSL")
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

    @Test
    void sslmodeIsReadFromTheQueryWhateverItsCaseAndPosition() {
        assertThat(TlsEnforcementInitializer.sslmode("jdbc:postgresql://h/db?a=1&SSLMODE=Verify-Full&b=2")).isEqualTo("verify-full");
        assertThat(TlsEnforcementInitializer.sslmode("jdbc:postgresql://h/db?sslmode=disable")).isEqualTo("disable");
        assertThat(TlsEnforcementInitializer.sslmode("jdbc:postgresql://h/db")).isNull();
        assertThat(TlsEnforcementInitializer.sslmode(null)).isNull();
    }
}
