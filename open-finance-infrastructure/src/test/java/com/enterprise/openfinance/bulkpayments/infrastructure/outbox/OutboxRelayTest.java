package com.enterprise.openfinance.bulkpayments.infrastructure.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.NotEnoughReplicasException;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
        OutboxEventJpaEntity first = row("FILE-1", "Payments.BulkFile.Accepted.v1");
        OutboxEventJpaEntity second = row("FILE-1", "Payments.BulkFile.Rejected.v1");
        first.setTraceparent("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        when(outbox.findPendingBatch(100)).thenReturn(List.of(first, second));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        assertThat(relay(CLOCK).relayOnce()).isEqualTo(2);

        assertThat(first.getStatus()).isEqualTo(OutboxEventJpaEntity.PUBLISHED);
        assertThat(first.getPublishedAt()).isEqualTo(NOW);
        assertThat(second.getStatus()).isEqualTo(OutboxEventJpaEntity.PUBLISHED);
        verify(kafka).send(argThat((ProducerRecord<String, String> record) ->
                record.topic().equals("evt.pay.bulk.v1")
                        && record.key().equals("FILE-1")
                        && new String(record.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8)
                        .equals("Payments.BulkFile.Accepted.v1")
                        && new String(record.headers().lastHeader("x-fapi-interaction-id").value(), StandardCharsets.UTF_8)
                        .equals("ix-1")
                        && record.headers().lastHeader("traceparent") != null));
        verify(kafka).send(argThat((ProducerRecord<String, String> record) ->
                new String(record.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8)
                        .equals("Payments.BulkFile.Rejected.v1")
                        && record.headers().lastHeader("traceparent") == null));
    }

    /** ADR-019 (owner decision 2026-10-08): one topic per aggregate, key = aggregateId, eventType/eventId/correlationId headers. */
    @Test
    void publishesEveryBulkFileEventToTheAggregateTopicWithKeyAndHeaders() throws Exception {
        BulkFileEventEnvelopeFactory envelopes = new BulkFileEventEnvelopeFactory(new com.fasterxml.jackson.databind.ObjectMapper());
        List<OutboxEventJpaEntity> rows = List.of(
                envelopes.toOutboxRow(new com.enterprise.openfinance.bulkpayments.domain.event.BulkFileAccepted(
                        UUID.randomUUID(), "FILE-1", 0L, NOW, "CONS-1", "TPP-001",
                        com.enterprise.openfinance.bulkpayments.domain.model.BulkIntegrityMode.PARTIAL_REJECTION, 2, 1, 1,
                        com.enterprise.openfinance.bulkpayments.domain.model.Money.of("10", "AED")), "ix-agg"),
                envelopes.toOutboxRow(new com.enterprise.openfinance.bulkpayments.domain.event.BulkFileRejected(
                        UUID.randomUUID(), "FILE-1", 1L, NOW, 2, 2,
                        com.enterprise.openfinance.bulkpayments.domain.event.BulkFileRejected.Reason.ALL_ITEMS_REJECTED),
                        "ix-agg"));
        when(outbox.findPendingBatch(100)).thenReturn(rows);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        assertThat(relay(CLOCK).relayOnce()).isEqualTo(2);

        org.mockito.ArgumentCaptor<ProducerRecord<String, String>> sent = org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, times(2)).send(sent.capture());
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        for (ProducerRecord<String, String> record : sent.getAllValues()) {
            com.fasterxml.jackson.databind.JsonNode envelope = json.readTree(record.value());
            assertThat(record.topic()).as("one topic per aggregate (ADR-019)").isEqualTo("evt.pay.bulk.v1");
            assertThat(record.key()).isEqualTo("FILE-1").isEqualTo(envelope.get("aggregateId").asText());
            assertThat(header(record, "eventType")).isEqualTo(envelope.get("eventType").asText());
            assertThat(header(record, "eventId")).isEqualTo(envelope.get("eventId").asText());
            assertThat(header(record, "correlationId")).isEqualTo(envelope.get("correlationId").asText()).isEqualTo("ix-agg");
        }
        assertThat(sent.getAllValues()).extracting(r -> header(r, "eventType"))
                .containsExactly("Payments.BulkFile.Accepted.v1", "Payments.BulkFile.Rejected.v1");
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    /** Review 5459741793 minor 2: no database transaction stays open while a send blocks on Kafka. */
    @Test
    void noTransactionIsOpenWhileASendBlocksAndOutcomesAreRecordedInShortTransactions() {
        OutboxEventJpaEntity first = row("FILE-1", "Payments.BulkFile.Accepted.v1");
        OutboxEventJpaEntity poison = row("FILE-2", "Payments.BulkFile.Accepted.v1");
        RecordingTransactions transactions = new RecordingTransactions();
        FakeLock lock = new FakeLock();
        List<Boolean> heldAtSend = new java.util.ArrayList<>();
        List<Boolean> openAtSend = new java.util.ArrayList<>();
        List<Boolean> openAtSave = new java.util.ArrayList<>();
        when(outbox.findPendingBatch(100)).thenReturn(List.of(first, poison));
        when(outbox.save(any(OutboxEventJpaEntity.class))).thenAnswer(invocation -> {
            openAtSave.add(transactions.open);
            return invocation.getArgument(0);
        });
        when(kafka.send(any(ProducerRecord.class)))
                .thenAnswer(invocation -> {
                    openAtSend.add(transactions.open);
                    heldAtSend.add(lock.held);
                    return CompletableFuture.completedFuture(mock(SendResult.class));
                })
                .thenAnswer(invocation -> {
                    openAtSend.add(transactions.open);
                    return CompletableFuture.failedFuture(new RecordTooLargeException("too large"));
                });

        assertThat(new OutboxRelay(outbox, kafka, transactions, lock, CLOCK, 100, Duration.ofSeconds(1),
                Duration.ofDays(7), new SimpleMeterRegistry()).relayOnce()).isEqualTo(1);

        assertThat(openAtSend).containsExactly(false, false);
        assertThat(heldAtSend).as("one relayer: the lock spans the sends").containsExactly(true);
        assertThat(lock.held).as("released after the run").isFalse();
        // Published and parked are each written in their own short transaction.
        assertThat(openAtSave).containsExactly(true, true);
        assertThat(first.getStatus()).isEqualTo(OutboxEventJpaEntity.PUBLISHED);
        assertThat(poison.getStatus()).isEqualTo(OutboxEventJpaEntity.PARKED);
    }

    @Test
    void theLockIsReleasedWhenRecordingAnOutcomeFails() {
        OutboxEventJpaEntity row = row("FILE-1", "Payments.BulkFile.Accepted.v1");
        FakeLock lock = new FakeLock();
        when(outbox.findPendingBatch(100)).thenReturn(List.of(row));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        when(outbox.save(any(OutboxEventJpaEntity.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("database gone"));
        OutboxRelay relay = new OutboxRelay(outbox, kafka, TransactionOperations.withoutTransaction(), lock, CLOCK, 100,
                Duration.ofSeconds(1), Duration.ofDays(7), new SimpleMeterRegistry());

        assertThatThrownBy(relay::relayOnce).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);

        assertThat(lock.held).isFalse();
        assertThat(lock.acquired).isEqualTo(1);
    }

    /** A lock that is always free; records whether it is held. */
    private static final class FakeLock implements RelayLock {
        boolean held;
        int acquired;

        @Override
        public java.util.Optional<Held> tryAcquire() {
            assertThat(held).as("not re-entered").isFalse();
            held = true;
            acquired++;
            return java.util.Optional.of(() -> held = false);
        }
    }

    /** Another replica holds the lock. */
    private static final RelayLock TAKEN = java.util.Optional::empty;

    /** Counts transactions and knows whether one is open, like TransactionTemplate around a callback. */
    private static final class RecordingTransactions implements TransactionOperations {
        boolean open;

        @Override
        public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
            assertThat(open).as("transactions are never nested").isFalse();
            open = true;
            try {
                return action.doInTransaction(new org.springframework.transaction.support.SimpleTransactionStatus());
            } finally {
                open = false;
            }
        }
    }

    @Test
    void retriableErrorsStopTheBatchWithoutMarkingAnyRowAndNeverParkHoweverLongTheyLast() {
        MutableClock clock = new MutableClock(NOW);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxEventJpaEntity failing = row("FILE-1", "Payments.BulkFile.Accepted.v1");
        OutboxEventJpaEntity later = row("FILE-2", "Payments.BulkFile.Accepted.v1");
        when(outbox.findPendingBatch(100)).thenReturn(List.of(failing, later));
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("Expiring 1 record(s)")))
                .thenReturn(CompletableFuture.failedFuture(new NotEnoughReplicasException("2 < 3")))
                .thenReturn(CompletableFuture.failedFuture(new NetworkException("broker unavailable")));
        OutboxRelay relay = relay(clock, registry);

        // Three days of outage, one attempt after each backoff: no time ceiling, nothing parks.
        for (int run = 0; run < 60; run++) {
            clock.set(NOW.plus(Duration.ofHours(run + 1L)));
            assertThat(relay.relayOnce()).isZero();
        }

        for (OutboxEventJpaEntity row : List.of(failing, later)) {
            assertThat(row.getStatus()).isEqualTo(OutboxEventJpaEntity.PENDING);
            assertThat(row.getParkedAt()).isNull();
            assertThat(row.getAttempts()).isZero();
            assertThat(row.getLastError()).isNull();
        }
        assertThat(failureCount(registry, "TimeoutException")).isEqualTo(1.0);
        assertThat(failureCount(registry, "NotEnoughReplicasException")).isEqualTo(1.0);
        assertThat(failureCount(registry, "NetworkException")).isEqualTo(58.0);
        verify(kafka, times(60)).send(any(ProducerRecord.class));
    }

    @Test
    void theRelaysOwnSendTimeoutIsNotAPayloadError() {
        OutboxEventJpaEntity row = row("FILE-1", "Payments.BulkFile.Accepted.v1");
        when(outbox.findPendingBatch(100)).thenReturn(List.of(row));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());

        assertThat(new OutboxRelay(outbox, kafka, TransactionOperations.withoutTransaction(), new FakeLock(), CLOCK, 100,
                Duration.ofMillis(5), Duration.ofDays(7), new SimpleMeterRegistry()).relayOnce()).isZero();

        assertThat(row.getStatus()).isEqualTo(OutboxEventJpaEntity.PENDING);
        assertThat(row.getAttempts()).isZero();
    }

    @Test
    void payloadErrorsParkTheRowAndTheBatchContinues() {
        List<RuntimeException> payload = List.of(
                new RecordTooLargeException("too large"),
                new SerializationException("cannot serialise"),
                new InvalidTopicException("bad topic"));
        for (RuntimeException failure : payload) {
            OutboxEventJpaEntity poison = row("FILE-1", "Payments.BulkFile.Accepted.v1");
            OutboxEventJpaEntity other = row("FILE-2", "Payments.BulkFile.Accepted.v1");
            SpringDataOutboxRepository repo = mock(SpringDataOutboxRepository.class);
            KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            when(repo.findPendingBatch(100)).thenReturn(List.of(poison, other));
            when(template.send(any(ProducerRecord.class)))
                    .thenReturn(CompletableFuture.failedFuture(failure))
                    .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

            assertThat(new OutboxRelay(repo, template, TransactionOperations.withoutTransaction(), new FakeLock(), CLOCK, 100,
                    Duration.ofSeconds(1), Duration.ofDays(7), registry)
                    .relayOnce()).as(failure.getClass().getSimpleName()).isEqualTo(1);

            String name = failure.getClass().getSimpleName();
            assertThat(poison.getStatus()).as(name).isEqualTo(OutboxEventJpaEntity.PARKED);
            assertThat(poison.getParkedAt()).isEqualTo(NOW);
            assertThat(poison.getParkedReason()).isEqualTo(OutboxEventJpaEntity.PAYLOAD_ERROR);
            assertThat(poison.getAttempts()).isEqualTo(1);
            // Only the exception class: Kafka messages may echo record content.
            assertThat(poison.getLastError()).isEqualTo(name);
            assertThat(other.getStatus()).isEqualTo(OutboxEventJpaEntity.PUBLISHED);
            assertThat(failureCount(registry, name)).isEqualTo(1.0);
            assertThat(poison.isParkCounted()).as("counted when the relay parked it").isTrue();
            assertThat(registry.get(OutboxRelay.PARKED_COUNTER).tag("exception", name).counter().count())
                    .as(name).isEqualTo(1.0);
        }
    }

    @Test
    void anOperatorParkIsCountedOnceByTheRelay() {
        OutboxEventJpaEntity operatorParked = row("FILE-1", "Payments.BulkFile.Accepted.v1");
        // What the runbook's operator SQL leaves behind: parked with a reason, not yet counted.
        org.springframework.test.util.ReflectionTestUtils.setField(operatorParked, "status", OutboxEventJpaEntity.PARKED);
        org.springframework.test.util.ReflectionTestUtils.setField(operatorParked, "parkedAt", NOW);
        org.springframework.test.util.ReflectionTestUtils.setField(operatorParked, "parkedReason", "INC-1: topic ACL");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(outbox.findUncountedParks()).thenReturn(List.of(operatorParked)).thenReturn(List.of());
        when(outbox.findPendingBatch(100)).thenReturn(List.of());
        OutboxRelay relay = relay(CLOCK, registry);

        relay.relayOnce();
        relay.relayOnce();

        assertThat(operatorParked.isParkCounted()).isTrue();
        assertThat(registry.get(OutboxRelay.PARKED_COUNTER).tag("exception", OutboxRelay.OPERATOR_PARK).counter().count())
                .isEqualTo(1.0);
        // Tagged by exception class only: no topic, event or file identifiers.
        assertThat(registry.get(OutboxRelay.PARKED_COUNTER).counters()).allSatisfy(counter ->
                assertThat(counter.getId().getTags()).extracting(Tag::getKey).containsExactly("exception"));
    }

    @Test
    void parksAreNotCountedByAReplicaWithoutTheRelayLock() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new OutboxRelay(outbox, kafka, TransactionOperations.withoutTransaction(), TAKEN, CLOCK, 100,
                Duration.ofSeconds(1), Duration.ofDays(7), registry).relayOnce();

        verify(outbox, never()).findUncountedParks();
        assertThat(registry.find(OutboxRelay.PARKED_COUNTER).counters()).isEmpty();
    }

    @Test
    void aParkedRowKeepsTheRestOfItsAggregateBlocked() {
        OutboxEventJpaEntity poison = row("FILE-1", "Payments.BulkFile.Accepted.v1");
        OutboxEventJpaEntity sameFile = row("FILE-1", "Payments.BulkFile.Rejected.v1");
        OutboxEventJpaEntity otherFile = row("FILE-2", "Payments.BulkFile.Accepted.v1");
        when(outbox.findPendingBatch(100)).thenReturn(List.of(poison, sameFile, otherFile));
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RecordTooLargeException("too large")))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        assertThat(relay(CLOCK, new SimpleMeterRegistry()).relayOnce()).isEqualTo(1);

        assertThat(poison.getStatus()).isEqualTo(OutboxEventJpaEntity.PARKED);
        assertThat(sameFile.getStatus()).isEqualTo(OutboxEventJpaEntity.PENDING);
        assertThat(sameFile.getAttempts()).isZero();
        assertThat(otherFile.getStatus()).isEqualTo(OutboxEventJpaEntity.PUBLISHED);
        verify(kafka, times(2)).send(any(ProducerRecord.class));
    }

    @Test
    void authorizationErrorsStopTheBatchWithoutMarkingAnyRowThenBackOff() {
        assertStopsWithoutMarking(new TopicAuthorizationException("denied"));
        assertStopsWithoutMarking(new SaslAuthenticationException("IAM refused"));
    }

    /**
     * Governance ruling: a missing topic or partition (UnknownTopicOrPartitionException, e.g. the topic was not
     * created yet or was deleted) stops the relay without parking: back off, alert, resume once the topic exists.
     * An invalid topic name (InvalidTopicException) stays a payload error and parks.
     */
    @Test
    void anUnknownTopicStopsTheRelayAndNeverParksButAnInvalidTopicParks() {
        assertStopsWithoutMarking(new org.apache.kafka.common.errors.UnknownTopicOrPartitionException("not found"));
        assertThat(OutboxRelay.isPayloadError(new ExecutionException(
                new org.apache.kafka.common.errors.UnknownTopicOrPartitionException("x")))).isFalse();
        assertThat(OutboxRelay.isPayloadError(new ExecutionException(new InvalidTopicException("x")))).isTrue();
    }

    @Test
    void unclassifiedErrorsStopTheBatchWithoutMarkingAnyRowThenBackOff() {
        assertStopsWithoutMarking(new IllegalStateException("unexpected"));
        // A producer that cannot even be built throws from send() itself.
        assertStopsWithoutMarking(new KafkaException("Failed to construct kafka producer"));
    }

    private void assertStopsWithoutMarking(RuntimeException failure) {
        MutableClock clock = new MutableClock(NOW);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxEventJpaEntity first = row("FILE-1", "Payments.BulkFile.Accepted.v1");
        OutboxEventJpaEntity later = row("FILE-2", "Payments.BulkFile.Accepted.v1");
        SpringDataOutboxRepository repo = mock(SpringDataOutboxRepository.class);
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        when(repo.findPendingBatch(100)).thenReturn(List.of(first, later));
        when(template.send(any(ProducerRecord.class))).thenThrow(failure)
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        OutboxRelay relay = relay(repo, template, clock, registry);
        String name = failure.getClass().getSimpleName();

        assertThat(relay.relayOnce()).as(name).isZero();

        for (OutboxEventJpaEntity row : List.of(first, later)) {
            assertThat(row.getStatus()).isEqualTo(OutboxEventJpaEntity.PENDING);
            assertThat(row.getAttempts()).isZero();
            assertThat(row.getLastError()).isNull();
        }
        assertThat(failureCount(registry, name)).isEqualTo(1.0);
        // Tagged by exception class only: no topic, event, file, payee or customer identifiers.
        assertThat(registry.get(OutboxRelay.FAILURE_COUNTER).counters()).allSatisfy(counter ->
                assertThat(counter.getId().getTags()).extracting(Tag::getKey).containsExactly("exception"));

        // Backoff: the next run within the window does not touch Kafka.
        assertThat(relay.relayOnce()).isZero();
        verify(template, times(1)).send(any(ProducerRecord.class));

        clock.set(NOW.plus(OutboxRelay.INITIAL_BACKOFF));
        assertThat(relay.relayOnce()).isEqualTo(2);
        assertThat(first.getStatus()).isEqualTo(OutboxEventJpaEntity.PUBLISHED);
    }

    @Test
    void backoffDoublesUpToItsCeilingAndResetsAfterASuccess() {
        MutableClock clock = new MutableClock(NOW);
        OutboxEventJpaEntity row = row("FILE-1", "Payments.BulkFile.Accepted.v1");
        when(outbox.findPendingBatch(100)).thenReturn(List.of(row));
        when(kafka.send(any(ProducerRecord.class))).thenThrow(new IllegalStateException("x"));
        OutboxRelay relay = relay(clock, new SimpleMeterRegistry());

        assertThat(relay.relayOnce()).isZero();
        assertThat(relay.pausedUntil()).isEqualTo(NOW.plus(OutboxRelay.INITIAL_BACKOFF));
        for (int run = 0; run < 10; run++) {
            clock.set(relay.pausedUntil());
            relay.relayOnce();
        }
        assertThat(Duration.between(clock.instant(), relay.pausedUntil())).isEqualTo(OutboxRelay.MAX_BACKOFF);
    }

    @Test
    void metricNamesAreThoseAgreedWithPlatformForAlerting() {
        assertThat(OutboxRelay.FAILURE_COUNTER).isEqualTo("outbox.send.failures");
        assertThat(OutboxRelay.PARKED_COUNTER).isEqualTo("outbox.parked.events");
    }

    @Test
    void classifiesOnlyPayloadErrorsAsParkable() {
        assertThat(OutboxRelay.isPayloadError(new ExecutionException(new RecordTooLargeException("x")))).isTrue();
        assertThat(OutboxRelay.isPayloadError(new ExecutionException(new SerializationException("x")))).isTrue();
        assertThat(OutboxRelay.isPayloadError(new ExecutionException(new InvalidTopicException("x")))).isTrue();
        assertThat(OutboxRelay.isPayloadError(new ExecutionException(new TopicAuthorizationException("x")))).isFalse();
        assertThat(OutboxRelay.isPayloadError(new ExecutionException(new NotEnoughReplicasException("x")))).isFalse();
        assertThat(OutboxRelay.isPayloadError(new java.util.concurrent.TimeoutException())).isFalse();
        assertThat(OutboxRelay.describe(new ExecutionException(new RecordTooLargeException("big"))))
                .isEqualTo("RecordTooLargeException");
    }

    @Test
    void anotherReplicaHoldingTheLockMeansNothingIsSent() {
        assertThat(new OutboxRelay(outbox, kafka, TransactionOperations.withoutTransaction(), TAKEN, CLOCK, 100,
                Duration.ofSeconds(1), Duration.ofDays(7), new SimpleMeterRegistry()).relayOnce()).isZero();

        verify(outbox, never()).findPendingBatch(any(Integer.class));
        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    @Test
    void interruptedSendStopsTheBatch() {
        OutboxEventJpaEntity row = row("FILE-1", "Payments.BulkFile.Accepted.v1");
        when(outbox.findPendingBatch(100)).thenReturn(List.of(row));
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        when(kafka.send(any(ProducerRecord.class))).thenReturn(future);
        Thread.currentThread().interrupt();

        assertThat(relay(CLOCK).relayOnce()).isZero();

        assertThat(Thread.interrupted()).isTrue();
        assertThat(row.getLastError()).isNull();
        assertThat(row.getAttempts()).isZero();
    }

    @Test
    void purgesPublishedRowsOlderThanRetention() {
        when(outbox.deletePublishedBefore(NOW.minus(Duration.ofDays(7)))).thenReturn(4);

        assertThat(relay(CLOCK).purgePublished()).isEqualTo(4);
    }

    @Test
    void rejectsNonPositiveLimits() {
        assertThatThrownBy(() -> new OutboxRelay(outbox, kafka, TransactionOperations.withoutTransaction(), new FakeLock(), CLOCK,
                0, Duration.ofSeconds(1), Duration.ofDays(1), new SimpleMeterRegistry()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private OutboxRelay relay(Clock clock) {
        return relay(clock, new SimpleMeterRegistry());
    }

    private OutboxRelay relay(Clock clock, SimpleMeterRegistry registry) {
        return relay(outbox, kafka, clock, registry);
    }

    private static OutboxRelay relay(SpringDataOutboxRepository repo, KafkaTemplate<String, String> template,
                                     Clock clock, SimpleMeterRegistry registry) {
        return new OutboxRelay(repo, template, TransactionOperations.withoutTransaction(), new FakeLock(), clock, 100,
                Duration.ofSeconds(1), Duration.ofDays(7), registry);
    }

    private static double failureCount(SimpleMeterRegistry registry, String exception) {
        return registry.get(OutboxRelay.FAILURE_COUNTER).tag("exception", exception).counter().count();
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

    private static OutboxEventJpaEntity row(String fileId, String eventType) {
        return new OutboxEventJpaEntity(UUID.randomUUID(), "BulkFile", fileId, 0L, eventType, "{}", "ix-1", NOW);
    }
}
