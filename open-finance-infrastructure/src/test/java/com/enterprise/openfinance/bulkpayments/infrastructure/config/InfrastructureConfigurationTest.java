package com.enterprise.openfinance.bulkpayments.infrastructure.config;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkSettings;
import com.enterprise.openfinance.bulkpayments.infrastructure.outbox.OutboxEventJpaEntity;
import com.enterprise.openfinance.bulkpayments.infrastructure.outbox.SpringDataOutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InfrastructureConfigurationTest {

    @Test
    void settingsComeFromTheProperties() {
        BulkPaymentsProcessingProperties processing = new BulkPaymentsProcessingProperties();
        processing.setBatchSize(250);
        processing.setMaxFileSizeBytes(2_000L);
        processing.setMaxBatchesPerRun(3);
        processing.setInterval(Duration.ofMillis(500));
        processing.setEnabled(false);
        BulkPaymentsCacheProperties cache = new BulkPaymentsCacheProperties();
        cache.setTtl(Duration.ofSeconds(10));

        BulkSettings settings = new BulkPaymentsConfiguration().bulkSettings(cache, processing);

        assertThat(settings.processingBatchSize()).isEqualTo(250);
        assertThat(settings.maxFileSizeBytes()).isEqualTo(2_000L);
        assertThat(settings.cacheTtl()).isEqualTo(Duration.ofSeconds(10));
        assertThat(processing.getMaxBatchesPerRun()).isEqualTo(3);
        assertThat(processing.getInterval()).isEqualTo(Duration.ofMillis(500));
        assertThat(processing.isEnabled()).isFalse();
        assertThat(new BulkPaymentsConfiguration().bulkPaymentsClock()).isNotNull();
    }

    @Test
    void outboxGaugesReportPendingParkedAndLag() {
        SpringDataOutboxRepository outbox = mock(SpringDataOutboxRepository.class);
        when(outbox.countByStatus(OutboxEventJpaEntity.PENDING)).thenReturn(5L);
        when(outbox.countByStatus(OutboxEventJpaEntity.PARKED)).thenReturn(1L);
        when(outbox.findOldestPendingOccurredAt()).thenReturn(Optional.of(Instant.parse("2026-02-09T09:59:00Z")));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Clock clock = Clock.fixed(Instant.parse("2026-02-09T10:00:00Z"), ZoneOffset.UTC);

        new OutboxConfiguration().outboxMetrics(registry, outbox, clock);

        assertThat(registry.get("outbox.pending.events").gauge().value()).isEqualTo(5.0);
        assertThat(registry.get("outbox.parked.rows").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("outbox.oldest.pending.age.seconds").gauge().value()).isEqualTo(60.0);

        when(outbox.findOldestPendingOccurredAt()).thenReturn(Optional.empty());
        assertThat(registry.get("outbox.oldest.pending.age.seconds").gauge().value()).isZero();
    }
}
