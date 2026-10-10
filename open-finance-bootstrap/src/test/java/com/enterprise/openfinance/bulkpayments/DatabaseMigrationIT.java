package com.enterprise.openfinance.bulkpayments;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deployed migration step: the Helm pre-install/pre-upgrade Job runs the
 * image with the argument "migrate". It runs Flyway as the schema owner into
 * the schema the DBA bootstrap created, grants the runtime role, starts no web
 * server, Kafka, security or HTTP clients, and exits 0. Runs against a scratch
 * schema so the shared one is untouched.
 */
class DatabaseMigrationIT {

    private static final String SCHEMA = "sc_pay_bulk_migration_job_it";

    @BeforeAll
    static void requireDatabase() {
        PostgresTestDatabase.assumeAvailable();
    }

    @BeforeEach
    @AfterEach
    void dropScratchSchema() {
        PostgresTestDatabase.owner().execute("drop schema if exists " + SCHEMA + " cascade");
    }

    @Test
    void migrateRunsFlywayAsTheOwnerGrantsTheRuntimeRoleAndExits() {
        PostgresTestDatabase.prepare(SCHEMA);

        int exitCode = BulkOrchestrationApplication.run(
                "migrate",
                arg("spring.datasource.url", PostgresTestDatabase.url()),
                arg("DB_USERNAME", PostgresTestDatabase.RUNTIME_ROLE),
                arg("spring.flyway.user", PostgresTestDatabase.ownerUser()),
                arg("spring.flyway.password", PostgresTestDatabase.ownerCredential()),
                arg("spring.flyway.schemas", SCHEMA),
                arg("spring.flyway.default-schema", SCHEMA));

        assertThat(exitCode).isZero();
        JdbcTemplate owner = PostgresTestDatabase.owner();
        assertThat(owner.queryForObject(
                "select count(*) from " + SCHEMA + ".flyway_schema_history where success", Integer.class))
                .isGreaterThanOrEqualTo(10);
        assertThat(owner.queryForObject(
                "select tableowner from pg_tables where schemaname = ? and tablename = 'bulk_file'", String.class, SCHEMA))
                .isEqualTo(PostgresTestDatabase.ownerUser());
        assertThat(privilege("INSERT", "bulk_file")).isTrue();
        assertThat(privilege("DELETE", "bulk_file")).isFalse();
        assertThat(privilege("DELETE", "outbox_event")).isTrue();
        assertThat(owner.queryForObject("select has_schema_privilege(?, ?, 'CREATE')", Boolean.class,
                PostgresTestDatabase.RUNTIME_ROLE, SCHEMA)).isFalse();
        assertThat(owner.queryForObject("select has_schema_privilege(?, ?, 'USAGE')", Boolean.class,
                PostgresTestDatabase.RUNTIME_ROLE, SCHEMA)).isTrue();
    }

    /**
     * ADR-019 expand step (governance round 3, item 4): V15 keeps outbox_event.topic, nullable and no longer
     * written; the relay ignores it (OutboxRelay.TOPIC) and a later versioned migration drops it. The column
     * comment names the migration that deprecated it.
     */
    @Test
    void migrateKeepsTheOutboxTopicColumnNullableAndDeprecatedSinceV15() {
        PostgresTestDatabase.prepare(SCHEMA);

        int exitCode = BulkOrchestrationApplication.run(
                "migrate",
                arg("spring.datasource.url", PostgresTestDatabase.url()),
                arg("DB_USERNAME", PostgresTestDatabase.RUNTIME_ROLE),
                arg("spring.flyway.user", PostgresTestDatabase.ownerUser()),
                arg("spring.flyway.password", PostgresTestDatabase.ownerCredential()),
                arg("spring.flyway.schemas", SCHEMA),
                arg("spring.flyway.default-schema", SCHEMA));

        assertThat(exitCode).isZero();
        JdbcTemplate owner = PostgresTestDatabase.owner();
        assertThat(owner.queryForObject("select is_nullable from information_schema.columns"
                + " where table_schema = ? and table_name = 'outbox_event' and column_name = 'topic'", String.class, SCHEMA))
                .as("expand only: the column stays, nullable").isEqualTo("YES");
        assertThat(owner.queryForObject("select col_description(c.oid, a.attnum) from pg_class c"
                + " join pg_namespace n on n.oid = c.relnamespace join pg_attribute a on a.attrelid = c.oid"
                + " where n.nspname = ? and c.relname = 'outbox_event' and a.attname = 'topic'", String.class, SCHEMA))
                .startsWith("Deprecated since V15,");
    }

    @Test
    void migrateFailsWithANonZeroExitCodeWhenTheSchemaWasNotBootstrapped() {
        int exitCode = BulkOrchestrationApplication.run(
                "migrate",
                arg("spring.datasource.url", PostgresTestDatabase.url()),
                arg("DB_USERNAME", PostgresTestDatabase.RUNTIME_ROLE),
                arg("spring.flyway.user", PostgresTestDatabase.ownerUser()),
                arg("spring.flyway.password", PostgresTestDatabase.ownerCredential()),
                arg("spring.flyway.schemas", SCHEMA),
                arg("spring.flyway.default-schema", SCHEMA));

        assertThat(exitCode).isNotZero();
        assertThat(PostgresTestDatabase.owner().queryForObject(
                "select count(*) from information_schema.schemata where schema_name = ?", Integer.class, SCHEMA)).isZero();
    }

    @Test
    void migrateFailsWithANonZeroExitCodeWhenTheDatabaseRefusesTheOwner() {
        PostgresTestDatabase.prepare(SCHEMA);

        int exitCode = BulkOrchestrationApplication.run(
                "migrate",
                arg("spring.datasource.url", PostgresTestDatabase.url()),
                arg("DB_USERNAME", PostgresTestDatabase.RUNTIME_ROLE),
                arg("spring.flyway.user", PostgresTestDatabase.ownerUser()),
                arg("spring.flyway.password", "wrong-value"),
                arg("spring.flyway.schemas", SCHEMA),
                arg("spring.flyway.default-schema", SCHEMA));

        assertThat(exitCode).isNotZero();
    }

    private static Boolean privilege(String privilege, String table) {
        return PostgresTestDatabase.owner().queryForObject("select has_table_privilege(?, ?, ?)", Boolean.class,
                PostgresTestDatabase.RUNTIME_ROLE, SCHEMA + "." + table, privilege);
    }

    private static String arg(String name, String value) {
        return "--" + name + "=" + value;
    }
}
