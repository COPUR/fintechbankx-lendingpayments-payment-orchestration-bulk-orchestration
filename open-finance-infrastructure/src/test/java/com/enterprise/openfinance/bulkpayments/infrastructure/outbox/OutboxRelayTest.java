package com.enterprise.openfinance.bulkpayments.infrastructure.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.NotEnoughReplicasException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-02-09T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final SpringDataOutboxRepository outbox = mock(SpringDataOutboxRepository.class);
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    @Test
    void publishesPendingRowsInOrderWithHeadersAndMarksThem() {
        OutboxEventJpaEntity first = row("FILE-1", "evt.pay.bulk.accepted.v1");
        OutboxEventJpaEntity second = row("FILE-1", "evt.pay.bulk.rejected.v1");
        first.setTraceparent("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        when(outbox.tryRelayLock(OutboxRelay.RELAY_LOCK_KEY)).thenReturn(true);
        when(outbox.findPendingBatch(100)).thenReturn(List.of(first, second));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        assertThat(relay(CLOCK).relayOnce()).isEqualTo(2);

        assertThat(first.getStatus()).isEqualTo(OutboxEventJpaEntity.PUBLISHED);
        assertThat(first.getPublishedAt()).isEqualTo(NOW);
        assertThat(second.getStatus()).isEqualTo(OutboxEventJpaEntity.PUBLISHED);
        verify(kafka).send(argThat((ProducerRecord<String, String> record) ->
                record.topic().equals("evt.pay.bulk.accepted.v1")
                        && record.key().equals("FILE-1")
                        && new String(record.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8)
                        .equals("Payments.BulkFile.Accepted.v1")
                        && new String(record.headers().lastHeader("x-fapi-interaction-id").value(), StandardCharsets.UTF_8)
                        .equals("ix-1")
                        && record.headers().lastHeader("traceparent") != null));
        verify(kafka).send(argThat((ProducerRecord<String, String> record) ->
                record.topic().equals("evt.pay.bulk.rejected.v1")
                        && record.headers().lastHeader("traceparent") == null));
    }

    @Test
    void retryableFailuresStopTheBatchAndNeverCountTowardParking() {
        OutboxEventJpaEntity failing = row("FILE-1", "evt.pay.bulk.accepted.v1");
        OutboxEventJpaEntity later = row("FILE-2", "evt.pay.bulk.accepted.v1");
        when(outbox.tryRelayLock(OutboxRelay.RELAY_LOCK_KEY)).thenReturn(true);
        when(outbox.findPendingBatch(100)).thenReturn(List.of(failing, later));
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("Expiring 1 record(s)")))
                .thenReturn(CompletableFuture.failedFuture(new NotEnoughReplicasException("2 < 3")))
                .thenReturn(CompletableFuture.failedFuture(new NetworkException("broker unavailable")));

        OutboxRelay relay = relay(CLOCK);
        for (int run = 0; run < 50; run++) {
            assertThat(relay.relayOnce()).isZero();
        }

        // Fifty failed runs of an outage within 24 h: still pending, never parked.
        assertThat(failing.getStatus()).isEqualTo(OutboxEventJpaEntity.PENDING);
        assertThat(failing.getParkedAt()).isNull();
        assertThat(failing.getAttempts()).isEqualTo(50);
        assertThat(failing.getFirstFailedAt()).isEqualTo(NOW);
        assertThat(failing.getLastError()).isEqualTo("NetworkException: broker unavailable");
        assertThat(later.getAttempts()).isZero();
        verify(kafka, times(50)).send(any(ProducerRecord.class));
    }

    @Test
    void theRelaysOwnSendTimeoutIsRetryable() {
        OutboxEventJpaEntity row = row("FILE-1", "evt.pay.bulk.accepted.v1");
        when(outbox.tryRelayLock(OutboxRelay.RELAY_LOCK_KEY)).thenReturn(true);
        when(outbox.findPendingBatch(100)).thenReturn(List.of(row));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());

        assertThat(new OutboxRelay(outbox, kafka, TransactionOperations.withoutTransaction(), CLOCK, 100,
                Duration.ofMillis(5), Duration.ofDays(7), Duration.ofHours(24)).relayOnce()).isZero();

        assertThat(row.getStatus()).isEqualTo(OutboxEventJpaEntity.PENDING);
        assertThat(row.getFirstFailedAt()).isEqualTo(NOW);
    }

    @Test
    void aRowFailingRetryablyForMoreThan24HoursFromItsFirstFailureIsParked() {
        MutableClock clock = new MutableClock(NOW);
        OutboxEventJpaEntity stuck = row("FILE-1", "evt.pay.bulk.accepted.v1");
        OutboxEventJpaEntity later = row("FILE-2", "evt.pay.bulk.accepted.v1");
        when(outbox.tryRelayLock(OutboxRelay.RELAY_LOCK_KEY)).thenReturn(true);
        when(outbox.findPendingBatch(100)).thenReturn(List.of(stuck, later));
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(call ->
                ((ProducerRecord<String, String>) call.getArgument(0)).key().equals("FILE-1")
                        ? CompletableFuture.failedFuture(new TimeoutException("metadata"))
                        : CompletableFuture.completedFuture(mock(SendResult.class)));
        OutboxRelay relay = relay(clock);

        assertThat(relay.relayOnce()).isZero();
        clock.set(NOW.plus(Duration.ofHours(24)));
        assertThat(relay.relayOnce()).isZero();
        assertThat(stuck.getStatus()).as("exactly 24 h is still within the ceiling").isEqualTo(OutboxEventJpaEntity.PENDING);

        clock.set(NOW.plus(Duration.ofHours(24)).plusSeconds(1));
        assertThat(relay.relayOnce()).isEqualTo(1);

        assertThat(stuck.getStatus()).isEqualTo(OutboxEventJpaEntity.PARKED);
        assertThat(stuck.getParkedAt()).isEqualTo(NOW.plus(Duration.ofHours(24)).plusSeconds(1));
        assertThat(stuck.getFirstFailedAt()).isEqualTo(NOW);
        assertThat(stuck.getAttempts()).isEqualTo(3);
        assertThat(later.getStatus()).isEqualTo(OutboxEventJpaEntity.PUBLISHED);
    }

    @Test
    void nonRetryableFailuresParkAtOnceAndLaterRowsStillPublish() {
        List<RuntimeException> permanent = List.of(
                new RecordTooLargeException("too large"),
                new SerializationException("cannot serialise"),
                new TopicAuthorizationException("denied"),
                new InvalidTopicException("bad topic"),
                new IllegalStateException("unexpected"));
        for (RuntimeException failure : permanent) {
            OutboxEventJpaEntity poison = row("FILE-1", "evt.pay.bulk.accepted.v1");
            OutboxEventJpaEntity later = row("FILE-2", "evt.pay.bulk.accepted.v1");
            SpringDataOutboxRepository repo = mock(SpringDataOutboxRepository.class);
            KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
            when(repo.tryRelayLock(OutboxRelay.RELAY_LOCK_KEY)).thenReturn(true);
            when(repo.findPendingBatch(100)).thenReturn(List.of(poison, later));
            when(template.send(any(ProducerRecord.class)))
                    .thenReturn(CompletableFuture.failedFuture(failure))
                    .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

            assertThat(new OutboxRelay(repo, template, TransactionOperations.withoutTransaction(), CLOCK, 100,
                    Duration.ofSeconds(1), Duration.ofDays(7), Duration.ofHours(24)).relayOnce())
                    .as(failure.getClass().getSimpleName()).isEqualTo(1);

            assertThat(poison.getStatus()).as(failure.getClass().getSimpleName()).isEqualTo(OutboxEventJpaEntity.PARKED);
            assertThat(poison.getParkedAt()).isEqualTo(NOW);
            assertThat(poison.getAttempts()).isEqualTo(1);
            assertThat(poison.getLastError()).startsWith(failure.getClass().getSimpleName() + ": ");
            assertThat(later.getStatus()).isEqualTo(OutboxEventJpaEntity.PUBLISHED);
        }
    }

    @Test
    void classifiesFailuresThroughTheirCauses() {
        assertThat(OutboxRelay.isRetryable(new ExecutionException(new NotEnoughReplicasException("x")))).isTrue();
        assertThat(OutboxRelay.isRetryable(new java.util.concurrent.TimeoutException())).isTrue();
        assertThat(OutboxRelay.isRetryable(new ExecutionException(new RecordTooLargeException("x")))).isFalse();
        assertThat(OutboxRelay.describe(new ExecutionException(new RecordTooLargeException("big"))))
                .isEqualTo("RecordTooLargeException: big");
    }

    @Test
    void anotherReplicaHoldingTheLockMeansNothingIsSent() {
        when(outbox.tryRelayLock(OutboxRelay.RELAY_LOCK_KEY)).thenReturn(false);

        assertThat(relay(CLOCK).relayOnce()).isZero();

        verify(outbox, never()).findPendingBatch(any(Integer.class));
        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    @Test
    void interruptedSendStopsTheBatch() {
        OutboxEventJpaEntity row = row("FILE-1", "evt.pay.bulk.accepted.v1");
        when(outbox.tryRelayLock(OutboxRelay.RELAY_LOCK_KEY)).thenReturn(true);
        when(outbox.findPendingBatch(100)).thenReturn(List.of(row));
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        when(kafka.send(any(ProducerRecord.class))).thenReturn(future);
        Thread.currentThread().interrupt();

        assertThat(relay(CLOCK).relayOnce()).isZero();

        assertThat(Thread.interrupted()).isTrue();
        assertThat(row.getLastError()).isEqualTo("interrupted");
    }

    @Test
    void purgesPublishedRowsOlderThanRetention() {
        when(outbox.deletePublishedBefore(NOW.minus(Duration.ofDays(7)))).thenReturn(4);

        assertThat(relay(CLOCK).purgePublished()).isEqualTo(4);
    }

    @Test
    void rejectsNonPositiveLimits() {
        assertThatThrownBy(() -> new OutboxRelay(outbox, kafka, TransactionOperations.withoutTransaction(), CLOCK,
                0, Duration.ofSeconds(1), Duration.ofDays(1), Duration.ofHours(24)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxRelay(outbox, kafka, TransactionOperations.withoutTransaction(), CLOCK,
                100, Duration.ofSeconds(1), Duration.ofDays(1), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private OutboxRelay relay(Clock clock) {
        return new OutboxRelay(outbox, kafka, TransactionOperations.withoutTransaction(), clock, 100,
                Duration.ofSeconds(1), Duration.ofDays(7), Duration.ofHours(24));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant at) {
            this.now = at;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static OutboxEventJpaEntity row(String fileId, String topic) {
        return new OutboxEventJpaEntity(UUID.randomUUID(), "BulkFile", fileId, 0L, "Payments.BulkFile.Accepted.v1",
                topic, "{}", "ix-1", NOW);
    }
}
