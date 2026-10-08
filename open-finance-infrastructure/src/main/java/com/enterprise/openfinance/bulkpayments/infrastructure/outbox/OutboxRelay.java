package com.enterprise.openfinance.bulkpayments.infrastructure.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Relays committed outbox rows to Kafka in insertion order.
 *
 * One replica relays at a time (Postgres advisory lock), so the service can
 * scale out without reordering a file's events. Consumers de-duplicate on
 * eventId, which makes the at-least-once delivery safe.
 *
 * Failures follow ADR-021 decision 4 (adr-runbooks #10, 421f7b5 and the ruling
 * e6dd76a):
 * <ul>
 *   <li>Payload errors that can never succeed for the row (RecordTooLargeException,
 *   SerializationException, InvalidTopicException): the row is PARKED with reason
 *   PAYLOAD_ERROR (exception class in last_error) and the batch continues. The
 *   parked row keeps its aggregate blocked: later rows of the same file are not
 *   sent (here and in {@link SpringDataOutboxRepository#findPendingBatch}) until
 *   an operator replays it, so a file's events never go out of order.</li>
 *   <li>Everything else (retriable Kafka errors and timeouts, authorization and
 *   SASL/IAM failures, a producer that cannot be built, anything unclassified):
 *   the batch stops without marking the row or anything after it and the relay
 *   backs off ({@link #INITIAL_BACKOFF} doubling to {@link #MAX_BACKOFF}). Such a
 *   row is never parked automatically, however long the failure lasts; only an
 *   operator parks it by hand with a recorded reason (runbook). The alert is
 *   outbox.oldest.pending.age.seconds.</li>
 * </ul>
 * Every failed send increments {@value #FAILURE_COUNTER} tagged by exception
 * class only. last_error and logs carry the exception class, never record
 * content, payee IBANs or customer identifiers.
 */
public class OutboxRelay {

    static final long RELAY_LOCK_KEY = 0x7061795F62756CL; // "pay_bul"
    static final Duration INITIAL_BACKOFF = Duration.ofSeconds(5);
    static final Duration MAX_BACKOFF = Duration.ofMinutes(5);
    static final String FAILURE_COUNTER = "outbox.send.failures";
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final SpringDataOutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final int batchSize;
    private final Duration sendTimeout;
    private final Duration retention;
    private final MeterRegistry registry;
    private Duration backoff = INITIAL_BACKOFF;
    private Instant pausedUntil = Instant.MIN;

    public OutboxRelay(SpringDataOutboxRepository outbox, KafkaTemplate<String, String> kafka,
                       TransactionOperations transactions, Clock clock, int batchSize,
                       Duration sendTimeout, Duration retention, MeterRegistry registry) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.outbox = outbox;
        this.kafka = kafka;
        this.transactions = transactions;
        this.clock = clock;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
        this.retention = retention;
        this.registry = registry;
    }

    /**
     * @return number of events published in this run
     */
    public synchronized int relayOnce() {
        if (clock.instant().isBefore(pausedUntil)) {
            return 0;
        }
        Integer published = transactions.execute(status -> {
            if (!outbox.tryRelayLock(RELAY_LOCK_KEY)) {
                return 0;
            }
            List<OutboxEventJpaEntity> batch = outbox.findPendingBatch(batchSize);
            Set<String> blockedAggregates = new HashSet<>();
            int sent = 0;
            for (OutboxEventJpaEntity row : batch) {
                if (blockedAggregates.contains(row.getAggregateId())) {
                    continue;
                }
                try {
                    kafka.send(toRecord(row)).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
                    row.markPublished(clock.instant());
                    sent++;
                    backoff = INITIAL_BACKOFF;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Instant now = clock.instant();
                    String failure = describe(e);
                    registry.counter(FAILURE_COUNTER, "exception", failure).increment();
                    if (!isPayloadError(e)) {
                        pause(now, row, failure);
                        break;
                    }
                    row.markFailed(failure);
                    row.park(now, OutboxEventJpaEntity.PAYLOAD_ERROR);
                    blockedAggregates.add(row.getAggregateId());
                    log.error("Outbox relay parked event {} for {} on a payload error ({}); its file's later events"
                            + " wait until it is replayed by hand", row.getEventId(), row.getTopic(), failure);
                }
            }
            return sent;
        });
        return published == null ? 0 : published;
    }

    private void pause(Instant now, OutboxEventJpaEntity row, String failure) {
        pausedUntil = now.plus(backoff);
        log.warn("Outbox relay stopped at event {} for {} ({}); no row marked, retrying after {}",
                row.getEventId(), row.getTopic(), failure, backoff);
        Duration doubled = backoff.multipliedBy(2);
        backoff = doubled.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : doubled;
    }

    /** Until when the relay is backing off after a non-payload failure. */
    Instant pausedUntil() {
        return pausedUntil;
    }

    public int purgePublished() {
        Integer deleted = transactions.execute(status -> outbox.deletePublishedBefore(clock.instant().minus(retention)));
        return deleted == null ? 0 : deleted;
    }

    /** ADR-021 decision 4 payload errors, looking through future and KafkaTemplate wrappers. */
    static boolean isPayloadError(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RecordTooLargeException || cause instanceof SerializationException
                    || cause instanceof InvalidTopicException) {
                return true;
            }
        }
        return false;
    }

    /** The underlying exception class only (Kafka messages may echo record content). */
    static String describe(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException
                || cause instanceof KafkaProducerException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName();
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
