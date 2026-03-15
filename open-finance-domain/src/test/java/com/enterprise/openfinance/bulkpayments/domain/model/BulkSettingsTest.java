package com.enterprise.openfinance.bulkpayments.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BulkSettingsTest {

    @Test
    void shouldCreateValidSettings() {
        BulkSettings settings = new BulkSettings(Duration.ofHours(24), Duration.ofSeconds(30), 10_000_000L, 2);

        assertThat(settings.idempotencyTtl()).isEqualTo(Duration.ofHours(24));
        assertThat(settings.cacheTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(settings.maxFileSizeBytes()).isEqualTo(10_000_000L);
        assertThat(settings.statusPollsToComplete()).isEqualTo(2);
    }

    @Test
    void shouldRejectInvalidSettings() {
        assertInvalid(null, Duration.ofSeconds(30), 10_000_000L, 2, "idempotencyTtl");
        assertInvalid(Duration.ofHours(24), null, 10_000_000L, 2, "cacheTtl");
        assertInvalid(Duration.ZERO, Duration.ofSeconds(30), 10_000_000L, 2, "idempotencyTtl");
        assertInvalid(Duration.ofHours(24), Duration.ZERO, 10_000_000L, 2, "cacheTtl");
        assertInvalid(Duration.ofHours(24), Duration.ofSeconds(30), 0L, 2, "maxFileSizeBytes");
        assertInvalid(Duration.ofHours(24), Duration.ofSeconds(30), 10_000_000L, 0, "statusPollsToComplete");
    }

    private static void assertInvalid(Duration idempotencyTtl,
                                      Duration cacheTtl,
                                      long maxFileSizeBytes,
                                      int statusPollsToComplete,
                                      String expectedField) {
        assertThatThrownBy(() -> new BulkSettings(
                idempotencyTtl,
                cacheTtl,
                maxFileSizeBytes,
                statusPollsToComplete
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedField);
    }
}
