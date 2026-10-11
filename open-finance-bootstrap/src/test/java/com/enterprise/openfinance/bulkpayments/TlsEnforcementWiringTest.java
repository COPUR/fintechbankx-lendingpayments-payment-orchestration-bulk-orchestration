package com.enterprise.openfinance.bulkpayments;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.SpringApplication;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Governance round 3, item 2: the startup TLS assertion is on by default (application.yml), switched off only
 * by the local profile and explicit test configuration, and registered through META-INF/spring.factories so
 * every SpringApplication of this module runs it: the service, the "migrate" Job and @SpringBootTest alike.
 */
class TlsEnforcementWiringTest {

    @Test
    void theAssertionIsOnByDefault() {
        assertThat(load("application.yml").getProperty("fintechbankx.tls.enforce")).isEqualTo("true");
    }

    @Test
    void onlyTheLocalProfileSwitchesItOff() {
        assertThat(load("application-local.yml").getProperty("fintechbankx.tls.enforce")).isEqualTo("false");
        assertThat(load("application-kafka-msk.yml").getProperty("fintechbankx.tls.enforce")).isNull();
        assertThat(load("application-kafka-strimzi.yml").getProperty("fintechbankx.tls.enforce")).isNull();
    }

    @Test
    void everySpringApplicationOfThisModuleRunsTheInitializer() {
        assertThat(new SpringApplication(BulkOrchestrationApplication.class).getInitializers())
                .extracting(initializer -> initializer.getClass().getSimpleName())
                .contains("TlsEnforcementInitializer");
    }

    /** The migration Job has no Kafka client: its run says so, and still asserts the datasource URL. */
    @Test
    void theMigrateRunDeclaresNoKafkaClient() {
        assertThat(BulkOrchestrationApplication.migrationArgs("migrate", "--spring.datasource.url=jdbc:x"))
                .containsExactly("--spring.datasource.url=jdbc:x", "--spring.flyway.enabled=true",
                        "--spring.kafka.bootstrap-servers=");
    }

    private static Properties load(String resource) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(resource));
        return factory.getObject();
    }
}
