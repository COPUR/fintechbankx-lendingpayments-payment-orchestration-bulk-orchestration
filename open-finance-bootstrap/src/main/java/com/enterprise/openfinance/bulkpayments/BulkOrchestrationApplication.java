package com.enterprise.openfinance.bulkpayments;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Arrays;

/**
 * svc-pay-bulk-orchestration: bulk payment files and their items, extracted
 * from the open-finance-context bulkpayments capability of
 * enterprise-loan-management-system.
 *
 * With the first argument "migrate" the image runs only the Flyway migrations
 * (as the schema owner, SPRING_FLYWAY_USER / SPRING_FLYWAY_PASSWORD) and
 * exits: the Helm pre-install/pre-upgrade Job. The service pods never hold the
 * owner credential and run with spring.flyway.enabled=false.
 */
@SpringBootApplication
public class BulkOrchestrationApplication {

    static final String MIGRATE = "migrate";

    public static void main(String[] args) {
        if (args.length > 0 && MIGRATE.equals(args[0])) {
            System.exit(run(args));
        }
        SpringApplication.run(BulkOrchestrationApplication.class, args);
    }

    /**
     * Runs the migrations and returns the process exit code (0 on success).
     * Only the DataSource and Flyway are configured: no web server, Kafka,
     * security, HTTP clients or application beans.
     */
    static int run(String... args) {
        SpringApplication migration = new SpringApplication(DatabaseMigration.class);
        migration.setWebApplicationType(WebApplicationType.NONE);
        migration.setBannerMode(Banner.Mode.OFF);
        try (ConfigurableApplicationContext context = migration.run(migrationArgs(args))) {
            return SpringApplication.exit(context);
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /**
     * The migrate run's arguments: the caller's, without the leading "migrate", plus Flyway switched on and
     * no Kafka client (the Job configures none, so the startup TLS assertion checks the datasource only).
     */
    static String[] migrationArgs(String... args) {
        int skip = args.length > 0 && MIGRATE.equals(args[0]) ? 1 : 0;
        String[] rest = Arrays.copyOfRange(args, skip, args.length);
        String[] withFlyway = Arrays.copyOf(rest, rest.length + 2);
        withFlyway[rest.length] = "--spring.flyway.enabled=true";
        withFlyway[rest.length + 1] = "--spring.kafka.bootstrap-servers=";
        return withFlyway;
    }

    /** Not a component (no stereotype), so the service's component scan never picks it up. */
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
    static class DatabaseMigration {
    }
}
