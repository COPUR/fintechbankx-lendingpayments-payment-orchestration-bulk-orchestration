package com.enterprise.openfinance.bulkpayments.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BulkSettingsTest {

    @Test
    void shouldCreateValidSettings() {
        BulkSettings settings = new BulkSettings(Duration.ofSeconds(30), 10_000_000L, 2);

        assertThat(settings.cacheTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(settings.maxFileSizeBytes()).isEqualTo(10_000_000L);
        assertThat(settings.processingBatchSize()).isEqualTo(2);
    }

    @Test
    void idempotencyKeysHaveNoTimeToLive() {
        assertThat(BulkSettings.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("cacheTtl", "maxFileSizeBytes", "processingBatchSize");
    }

    @Test
    void shouldRejectInvalidSettings() {
        assertInvalid(null, 10_000_000L, 2, "cacheTtl");
        assertInvalid(Duration.ZERO, 10_000_000L, 2, "cacheTtl");
        assertInvalid(Duration.ofSeconds(30), 0L, 2, "maxFileSizeBytes");
        assertInvalid(Duration.ofSeconds(30), 10_000_000L, 0, "processingBatchSize");
    }

    private static void assertInvalid(Duration cacheTtl,
                                      long maxFileSizeBytes,
                                      int processingBatchSize,
                                      String expectedField) {
        assertThatThrownBy(() -> new BulkSettings(
                cacheTtl,
                maxFileSizeBytes,
                processingBatchSize
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedField);
    }
}
