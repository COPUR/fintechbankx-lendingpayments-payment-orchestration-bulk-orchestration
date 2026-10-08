package com.enterprise.openfinance.bulkpayments.infrastructure.config;

import com.enterprise.openfinance.bulkpayments.infrastructure.outbox.BulkFileEventEnvelopeFactory;
import com.enterprise.openfinance.bulkpayments.infrastructure.outbox.OutboxEventJpaEntity;
import com.enterprise.openfinance.bulkpayments.infrastructure.outbox.OutboxRelay;
import com.enterprise.openfinance.bulkpayments.infrastructure.outbox.SpringDataOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Configuration
public class OutboxConfiguration {

    @Bean
    BulkFileEventEnvelopeFactory bulkFileEventEnvelopeFactory(ObjectMapper objectMapper) {
        return new BulkFileEventEnvelopeFactory(objectMapper);
    }

    /**
     * Outbox health, tagged service=svc-pay-bulk-orchestration by the common tags.
     * Platform names (Kafka guide 5f7d546): outbox_oldest_pending_age_seconds
     * (the stalled-relay alert: non-payload failures never park), and the
     * counters outbox_send_failures_total{exception} and
     * outbox_parked_events_total{exception} (alert on any increase), registered
     * by OutboxRelay. Service gauges: outbox_pending_events and
     * outbox_parked_rows (rows parked right now; each blocks its file).
     */
    @Bean
    OutboxMetrics outboxMetrics(MeterRegistry registry, SpringDataOutboxRepository outbox, Clock clock) {
        Gauge.builder("outbox.pending.events", outbox, repo -> repo.countByStatus(OutboxEventJpaEntity.PENDING))
                .description("Bulk file events written to the outbox but not yet published to Kafka")
                .register(registry);
        // Not "outbox.parked.events": that is the counter (outbox_parked_events_total) and
        // Prometheus refuses a gauge and a counter sharing a base name.
        Gauge.builder("outbox.parked.rows", outbox, repo -> repo.countByStatus(OutboxEventJpaEntity.PARKED))
                .description("Bulk file events parked on a payload error or by an operator; each blocks its file's later events")
                .register(registry);
        Gauge.builder("outbox.oldest.pending.age.seconds", outbox, repo -> repo.findOldestPendingOccurredAt()
                        .map(oldest -> (double) Duration.between(oldest, Instant.now(clock)).toSeconds())
                        .orElse(0.0))
                .description("Age of the oldest event not yet published; the alert for a stalled relay (ADR-021"
                        + " decision 4: non-payload failures never park)")
                .baseUnit("seconds")
                .register(registry);
        return new OutboxMetrics();
    }

    /** Marker bean so the gauges register once. */
    static final class OutboxMetrics {
    }

    /**
     * The relay runs in every replica; the advisory lock lets only one of
     * them publish at a time. Off by default until the topics exist on the
     * platform cluster (runbook step 4): events wait in the outbox.
     */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "openfinance.bulkpayments.outbox.relay.enabled", havingValue = "true")
    static class RelayConfiguration {

        @Bean
        OutboxRelay outboxRelay(SpringDataOutboxRepository outbox,
                                KafkaTemplate<String, String> kafka,
                                PlatformTransactionManager transactionManager,
                                Clock clock,
                                @Value("${openfinance.bulkpayments.outbox.relay.batch-size:100}") int batchSize,
                                @Value("${openfinance.bulkpayments.outbox.relay.send-timeout:PT35S}") Duration sendTimeout,
                                @Value("${openfinance.bulkpayments.outbox.retention:P7D}") Duration retention,
                                MeterRegistry registry) {
            return new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager), clock, batchSize,
                    sendTimeout, retention, registry);
        }

        @Bean
        RelaySchedule relaySchedule(OutboxRelay relay) {
            return new RelaySchedule(relay);
        }
    }

    static class RelaySchedule {
        private final OutboxRelay relay;

        RelaySchedule(OutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(fixedDelayString = "${openfinance.bulkpayments.outbox.relay.interval:PT1S}")
        void relay() {
            relay.relayOnce();
        }

        @Scheduled(cron = "${openfinance.bulkpayments.outbox.purge-cron:0 15 3 * * *}")
        void purge() {
            relay.purgePublished();
        }
    }
}
