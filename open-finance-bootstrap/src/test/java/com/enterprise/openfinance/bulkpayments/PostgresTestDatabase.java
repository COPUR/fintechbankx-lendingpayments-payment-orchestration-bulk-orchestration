package com.enterprise.openfinance.bulkpayments;

import org.junit.jupiter.api.Assumptions;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * PostgreSQL for the integration tests, from TEST_DB_URL / TEST_DB_USERNAME /
 * TEST_DB_PASSWORD (a service container in required-gates.yml). Without
 * TEST_DB_URL the tests are skipped locally but FAIL when CI=true, so a
 * pipeline that lost its database cannot pass green.
 */
final class PostgresTestDatabase {

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
        registry.add("spring.datasource.url", () -> System.getenv("TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> env("TEST_DB_USERNAME", "postgres"));
        registry.add("spring.datasource.password", () -> env("TEST_DB_PASSWORD", "postgres"));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
