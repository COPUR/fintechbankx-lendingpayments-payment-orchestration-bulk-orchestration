package com.enterprise.openfinance.bulkpayments.infrastructure.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Relays committed outbox rows to Kafka in insertion order.
 *
 * One replica relays at a time (Postgres advisory lock), so the service can
 * scale out without reordering a file's events. A failed send stops the batch
 * and is retried on the next run; after {@code maxAttempts} failures the row
 * is PARKED so one poison event cannot block every later event. Consumers
 * de-duplicate on eventId, which makes the at-least-once delivery safe.
 */
public class OutboxRelay {

    static final long RELAY_LOCK_KEY = 0x7061795F62756CL; // "pay_bul"
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final SpringDataOutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration sendTimeout;
    private final Duration retention;

    public OutboxRelay(SpringDataOutboxRepository outbox, KafkaTemplate<String, String> kafka,
                       TransactionOperations transactions, Clock clock, int batchSize, int maxAttempts,
                       Duration sendTimeout, Duration retention) {
        if (batchSize <= 0 || maxAttempts <= 0) {
            throw new IllegalArgumentException("batchSize and maxAttempts must be positive");
        }
        this.outbox = outbox;
        this.kafka = kafka;
        this.transactions = transactions;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.sendTimeout = sendTimeout;
        this.retention = retention;
    }

    /**
     * @return number of events published in this run
     */
    public int relayOnce() {
        Integer published = transactions.execute(status -> {
            if (!outbox.tryRelayLock(RELAY_LOCK_KEY)) {
                return 0;
            }
            List<OutboxEventJpaEntity> batch = outbox.findPendingBatch(batchSize);
            int sent = 0;
            for (OutboxEventJpaEntity row : batch) {
                try {
                    kafka.send(toRecord(row)).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
                    row.markPublished(clock.instant());
                    sent++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    row.markFailed("interrupted", maxAttempts, clock.instant());
                    break;
                } catch (Exception e) {
                    boolean parked = row.markFailed(e.getClass().getSimpleName(), maxAttempts, clock.instant());
                    if (parked) {
                        log.error("Outbox event {} to {} parked after {} attempts; later events continue",
                                row.getEventId(), row.getTopic(), row.getAttempts(), e);
                        continue;
                    }
                    log.warn("Outbox relay could not publish event {} to {}; will retry",
                            row.getEventId(), row.getTopic(), e);
                    break;
                }
            }
            return sent;
        });
        return published == null ? 0 : published;
    }

    public int purgePublished() {
        Integer deleted = transactions.execute(status -> outbox.deletePublishedBefore(clock.instant().minus(retention)));
        return deleted == null ? 0 : deleted;
    }

    static ProducerRecord<String, String> toRecord(OutboxEventJpaEntity row) {
        ProducerRecord<String, String> record = new ProducerRecord<>(row.getTopic(), row.getAggregateId(), row.getPayload());
        record.headers().add("eventType", row.getEventType().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventId", row.getEventId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("correlationId", row.getCorrelationId().getBytes(StandardCharsets.UTF_8));
        record.headers().add("x-fapi-interaction-id", row.getCorrelationId().getBytes(StandardCharsets.UTF_8));
        if (row.getTraceparent() != null) {
            record.headers().add("traceparent", row.getTraceparent().getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
