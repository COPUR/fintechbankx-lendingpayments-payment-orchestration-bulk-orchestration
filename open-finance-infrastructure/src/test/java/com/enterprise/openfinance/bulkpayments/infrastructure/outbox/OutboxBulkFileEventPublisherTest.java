package com.enterprise.openfinance.bulkpayments.infrastructure.outbox;

import com.enterprise.openfinance.bulkpayments.domain.model.Money;
import com.enterprise.openfinance.bulkpayments.domain.event.BulkFileEvent;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkFile;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkFileStatus;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkIntegrityMode;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkItemResult;
import com.enterprise.openfinance.bulkpayments.domain.model.ParsedBulkFile;
import com.enterprise.openfinance.bulkpayments.infrastructure.rest.InteractionIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OutboxBulkFileEventPublisherTest {

    private final SpringDataOutboxRepository outbox = mock(SpringDataOutboxRepository.class);
    private final OutboxBulkFileEventPublisher publisher =
            new OutboxBulkFileEventPublisher(outbox, new BulkFileEventEnvelopeFactory(new ObjectMapper()));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesTheRequestInteractionIdAsCorrelationId() {
        MDC.put(InteractionIdFilter.MDC_KEY, "ix-42");
        MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put("spanId", "00f067aa0ba902b7");
        BulkFile file = file();

        publisher.publish(file, file.pullDomainEvents());

        ArgumentCaptor<List<OutboxEventJpaEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(outbox).saveAll(rows.capture());
        assertThat(rows.getValue()).singleElement().extracting(OutboxEventJpaEntity::getCorrelationId).isEqualTo("ix-42");
        assertThat(rows.getValue().get(0).getTraceparent())
                .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    }

    @Test
    @SuppressWarnings("unchecked")
    void fallsBackToTheFileIdOutsideARequest() {
        BulkFile file = file();

        publisher.publish(file, file.pullDomainEvents());

        ArgumentCaptor<List<OutboxEventJpaEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(outbox).saveAll(rows.capture());
        assertThat(rows.getValue()).singleElement().extracting(OutboxEventJpaEntity::getCorrelationId).isEqualTo("FILE-1");
        assertThat(rows.getValue().get(0).getTraceparent()).isNull();
    }

    @Test
    void writesNothingWithoutEvents() {
        publisher.publish(file(), List.<BulkFileEvent>of());

        verify(outbox, never()).saveAll(any());
    }

    @Test
    void malformedTraceIdsAreNotForwarded() {
        MDC.put("traceId", "not-hex");
        MDC.put("spanId", "00f067aa0ba902b7");

        assertThat(OutboxBulkFileEventPublisher.currentTraceparent()).isNull();
    }

    private static BulkFile file() {
        ParsedBulkFile parsed = new ParsedBulkFile(
                List.of(BulkItemResult.accepted(1, "INS-1", "AE120001000000000000000001", Money.of("10", "AED"))),
                1, 1, 0, Money.of("10", "AED"), Money.of("10", "AED"), BulkFileStatus.VALIDATED);
        return BulkFile.accept("FILE-1", "CONS-1", "TPP-001", "IDEMP-1", "hash", "f.csv",
                BulkIntegrityMode.PARTIAL_REJECTION, parsed, Instant.parse("2026-02-09T10:00:00Z"));
    }
}
