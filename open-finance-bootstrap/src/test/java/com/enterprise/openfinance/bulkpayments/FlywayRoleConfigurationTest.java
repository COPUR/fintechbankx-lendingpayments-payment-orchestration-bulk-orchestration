package com.enterprise.openfinance.bulkpayments;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Platform contract "Database roles": Flyway runs as the schema owner (the
 * migration Job's secret, SPRING_FLYWAY_USER / SPRING_FLYWAY_PASSWORD) into a
 * schema the DBA bootstrap created, and grants the runtime role (DB_USERNAME)
 * only DML. The service pods never migrate. Without owner credentials (local
 * runs) Flyway falls back to the app's own.
 */
class FlywayRoleConfigurationTest {

    @Test
    void theMigrationJobRunsFlywayAsTheOwnerAndGrantsTheRuntimeRole() throws Exception {
        FlywayProperties flyway = flyway(Map.of(
                "SPRING_DATASOURCE_PASSWORD", "runtime-value",
                "SPRING_FLYWAY_ENABLED", "true",
                "SPRING_FLYWAY_USER", "payment_bulk_owner",
                "SPRING_FLYWAY_PASSWORD", "owner-value"));

        assertThat(flyway.isEnabled()).isTrue();
        assertThat(flyway.getUser()).isEqualTo("payment_bulk_owner");
        assertThat(flyway.getPassword()).isEqualTo("owner-value");
        assertThat(flyway.getPlaceholders()).containsEntry("runtime_role", "payment_bulk_app");
        assertThat(flyway.getSchemas()).containsExactly("sc_pay_bulk_orchestration");
        assertThat(flyway.isCreateSchemas()).as("the DBA bootstrap creates the schema").isFalse();
    }

    @Test
    void theServicePodsNeverMigrateAndHoldOnlyTheRuntimeRole() throws Exception {
        FlywayProperties flyway = flyway(Map.of(
                "DB_USERNAME", "payment_bulk_app",
                "SPRING_DATASOURCE_PASSWORD", "runtime-value",
                "SPRING_FLYWAY_ENABLED", "false"));

        assertThat(flyway.isEnabled()).isFalse();
        assertThat(flyway.getUser()).as("falls back to the datasource user").isNull();
        assertThat(flyway.getPlaceholders()).containsEntry("runtime_role", "payment_bulk_app");
    }

    private static FlywayProperties flyway(Map<String, Object> podEnv) throws Exception {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("pod-env", podEnv));
        for (PropertySource<?> document : documents) {
            if (document.getProperty("spring.config.activate.on-profile") == null) {
                environment.getPropertySources().addAfter("pod-env", document);
            }
        }
        return Binder.get(environment).bind("spring.flyway", FlywayProperties.class).orElseGet(FlywayProperties::new);
    }
}
