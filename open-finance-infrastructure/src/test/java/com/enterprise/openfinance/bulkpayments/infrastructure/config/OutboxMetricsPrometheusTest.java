package com.enterprise.openfinance.bulkpayments.infrastructure.config;

import com.enterprise.openfinance.bulkpayments.infrastructure.outbox.OutboxEventJpaEntity;
import com.enterprise.openfinance.bulkpayments.infrastructure.outbox.OutboxRelay;
import com.enterprise.openfinance.bulkpayments.infrastructure.outbox.SpringDataOutboxRepository;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The outbox meters under the names in the platform Kafka guide (5f7d546):
 * gauge outbox_oldest_pending_age_seconds, counters outbox_send_failures_total
 * and outbox_parked_events_total, both tagged exception only. They must all
 * register in one Prometheus registry (a gauge and a counter sharing a base
 * name would be refused), alongside the pending and parked-rows gauges.
 */
class OutboxMetricsPrometheusTest {

    private static final Instant NOW = Instant.parse("2026-02-09T10:00:00Z");

    @Test
    @SuppressWarnings("unchecked")
    void allOutboxMetersRegisterUnderTheirPlatformPrometheusNames() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        SpringDataOutboxRepository outbox = mock(SpringDataOutboxRepository.class);
        when(outbox.countByStatus(OutboxEventJpaEntity.PARKED)).thenReturn(2L);
        when(outbox.findOldestPendingOccurredAt()).thenReturn(Optional.empty());
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        new OutboxConfiguration().outboxMetrics(registry, outbox, clock);
        OutboxRelay relay = new OutboxRelay(outbox, mock(KafkaTemplate.class), TransactionOperations.withoutTransaction(),
                clock, 100, Duration.ofSeconds(1), Duration.ofDays(7), registry);

        relay.recordSendFailure(new org.apache.kafka.common.errors.TimeoutException("x"));
        relay.recordParked("RecordTooLargeException");

        String scrape = registry.scrape();
        assertThat(scrape)
                .contains("outbox_oldest_pending_age_seconds ")
                .contains("outbox_send_failures_total{exception=\"TimeoutException\"} 1.0")
                .contains("outbox_parked_events_total{exception=\"RecordTooLargeException\"} 1.0")
                .contains("outbox_parked_rows 2.0")
                .contains("outbox_pending_events ");
    }
}
