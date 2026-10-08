package com.enterprise.openfinance.bulkpayments;

import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * PostgreSQL for the integration tests, from TEST_DB_URL / TEST_DB_USERNAME /
 * TEST_DB_PASSWORD (a service container in required-gates.yml). Without
 * TEST_DB_URL the tests are skipped locally but FAIL when CI=true, so a
 * pipeline that lost its database cannot pass green.
 *
 * Runs the service with the two roles of production: Flyway as the schema
 * owner (the database's test user, through spring.flyway.user; in-process
 * here, a Helm hook Job when deployed) and the application as a separate
 * runtime role (DB_USERNAME) holding only the grants of V11. As in the DBA
 * bootstrap, the schema exists before Flyway runs (create-schemas is false).
 * The test user needs CREATEROLE to create the runtime role.
 */
final class PostgresTestDatabase {

    static final String SCHEMA = "sc_pay_bulk_orchestration";
    static final String RUNTIME_ROLE = "bulk_runtime_it";
    private static final String RUNTIME_CREDENTIAL = "bulk_runtime_it";

    private PostgresTestDatabase() {
    }

    /** Call from a static @BeforeAll. */
    static void assumeAvailable() {
        if (hasDatabase()) {
            return;
        }
        if ("true".equalsIgnoreCase(System.getenv("CI"))) {
            throw new IllegalStateException("CI=true but TEST_DB_URL is not set: PostgreSQL integration tests cannot run");
        }
        Assumptions.abort("Set TEST_DB_URL (and TEST_DB_USERNAME / TEST_DB_PASSWORD) to run PostgreSQL integration tests");
    }

    static boolean hasDatabase() {
        String url = System.getenv("TEST_DB_URL");
        return url != null && !url.isBlank();
    }

    static void register(DynamicPropertyRegistry registry) {
        if (!hasDatabase()) {
            return;
        }
        prepare(SCHEMA);
        registry.add("spring.datasource.url", PostgresTestDatabase::url);
        // Runtime role: what the pods connect as.
        registry.add("DB_USERNAME", () -> RUNTIME_ROLE);
        registry.add("spring.datasource.password", () -> RUNTIME_CREDENTIAL);
        // Schema owner: what Flyway connects as (the migration Job's secret).
        registry.add("spring.flyway.user", PostgresTestDatabase::ownerUser);
        registry.add("spring.flyway.password", PostgresTestDatabase::ownerCredential);
    }

    /**
     * What the DBA bootstrap does once per environment: the runtime role
     * exists and the schema exists, owned by the schema owner.
     */
    static synchronized void prepare(String schema) {
        owner().execute("""
                do $$
                begin
                    if not exists (select 1 from pg_roles where rolname = '%1$s') then
                        create role %1$s login password '%2$s';
                    end if;
                end $$
                """.formatted(RUNTIME_ROLE, RUNTIME_CREDENTIAL));
        owner().execute("create schema if not exists " + schema);
    }

    static String url() {
        return System.getenv("TEST_DB_URL");
    }

    static String ownerUser() {
        return env("TEST_DB_USERNAME", "postgres");
    }

    static String ownerCredential() {
        return env("TEST_DB_PASSWORD", "postgres");
    }

    /** The schema owner's connection, for test set-up the runtime role may not do. */
    static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(url(), ownerUser(), ownerCredential()));
    }

    /** A plain connection as the runtime role, outside the application. */
    static JdbcTemplate runtime() {
        return new JdbcTemplate(new DriverManagerDataSource(url(), RUNTIME_ROLE, RUNTIME_CREDENTIAL));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
