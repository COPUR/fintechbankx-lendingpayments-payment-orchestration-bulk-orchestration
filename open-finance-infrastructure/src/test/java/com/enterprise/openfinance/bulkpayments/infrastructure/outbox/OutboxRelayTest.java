package com.enterprise.openfinance.bulkpayments.infrastructure.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
        OutboxEventJpaEntity second = row("FILE-1", "evt.pay.bulk.completed.v1");
        first.setTraceparent("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        when(outbox.tryRelayLock(OutboxRelay.RELAY_LOCK_KEY)).thenReturn(true);
        when(outbox.findPendingBatch(100)).thenReturn(List.of(first, second));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        assertThat(relay(10).relayOnce()).isEqualTo(2);

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
                record.topic().equals("evt.pay.bulk.completed.v1")
                        && record.headers().lastHeader("traceparent") == null));
    }

    @Test
    void failedSendStopsTheBatchAndIsRetriedUntilMaxAttempts() {
        OutboxEventJpaEntity failing = row("FILE-1", "evt.pay.bulk.accepted.v1");
        OutboxEventJpaEntity later = row("FILE-2", "evt.pay.bulk.accepted.v1");
        when(outbox.tryRelayLock(OutboxRelay.RELAY_LOCK_KEY)).thenReturn(true);
        when(outbox.findPendingBatch(100)).thenReturn(List.of(failing, later));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThat(relay(3).relayOnce()).isZero();

        assertThat(failing.getStatus()).isEqualTo(OutboxEventJpaEntity.PENDING);
        assertThat(failing.getAttempts()).isEqualTo(1);
        assertThat(failing.getLastError()).isEqualTo("ExecutionException");
        assertThat(later.getAttempts()).isZero();
        verify(kafka, times(1)).send(any(ProducerRecord.class));
    }

    @Test
    void poisonRowIsParkedAfterMaxAttemptsAndLaterRowsStillPublish() {
        OutboxEventJpaEntity poison = row("FILE-1", "evt.pay.bulk.accepted.v1");
        OutboxEventJpaEntity later = row("FILE-2", "evt.pay.bulk.accepted.v1");
        when(outbox.tryRelayLock(OutboxRelay.RELAY_LOCK_KEY)).thenReturn(true);
        when(outbox.findPendingBatch(100)).thenReturn(List.of(poison, later));
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("record too large")))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("record too large")))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        OutboxRelay relay = relay(2);
        assertThat(relay.relayOnce()).isZero();
        assertThat(poison.getStatus()).isEqualTo(OutboxEventJpaEntity.PENDING);

        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(poison.getStatus()).isEqualTo(OutboxEventJpaEntity.PARKED);
        assertThat(poison.getParkedAt()).isEqualTo(NOW);
        assertThat(poison.getAttempts()).isEqualTo(2);
        assertThat(later.getStatus()).isEqualTo(OutboxEventJpaEntity.PUBLISHED);
    }

    @Test
    void anotherReplicaHoldingTheLockMeansNothingIsSent() {
        when(outbox.tryRelayLock(OutboxRelay.RELAY_LOCK_KEY)).thenReturn(false);

        assertThat(relay(10).relayOnce()).isZero();

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

        assertThat(relay(10).relayOnce()).isZero();

        assertThat(Thread.interrupted()).isTrue();
        assertThat(row.getLastError()).isEqualTo("interrupted");
    }

    @Test
    void purgesPublishedRowsOlderThanRetention() {
        when(outbox.deletePublishedBefore(NOW.minus(Duration.ofDays(7)))).thenReturn(4);

        assertThat(relay(10).purgePublished()).isEqualTo(4);
    }

    @Test
    void rejectsNonPositiveLimits() {
        assertThatThrownBy(() -> new OutboxRelay(outbox, kafka, TransactionOperations.withoutTransaction(), CLOCK,
                0, 1, Duration.ofSeconds(1), Duration.ofDays(1))).isInstanceOf(IllegalArgumentException.class);
    }

    private OutboxRelay relay(int maxAttempts) {
        return new OutboxRelay(outbox, kafka, TransactionOperations.withoutTransaction(), CLOCK, 100, maxAttempts,
                Duration.ofSeconds(1), Duration.ofDays(7));
    }

    private static OutboxEventJpaEntity row(String fileId, String topic) {
        return new OutboxEventJpaEntity(UUID.randomUUID(), "BulkFile", fileId, 0L, "Payments.BulkFile.Accepted.v1",
                topic, "{}", "ix-1", NOW);
    }
}
