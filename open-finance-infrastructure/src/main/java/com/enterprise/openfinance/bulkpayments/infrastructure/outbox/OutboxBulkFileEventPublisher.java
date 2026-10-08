package com.enterprise.openfinance.bulkpayments.infrastructure.outbox;

import com.enterprise.openfinance.bulkpayments.domain.event.BulkFileEvent;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkFile;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkFileEventPort;
import com.enterprise.openfinance.bulkpayments.infrastructure.rest.InteractionIdFilter;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Transactional outbox: writes each event's envelope in the caller's
 * transaction (MANDATORY), so the file and its events commit or roll back
 * together. {@link OutboxRelay} ships them to Kafka afterwards.
 *
 * Correlation id: the request's x-fapi-interaction-id; for events raised by
 * the background processor (no request) the file id, so every event of a
 * file can be traced together.
 */
@Component
public class OutboxBulkFileEventPublisher implements BulkFileEventPort {

    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern SPAN_ID = Pattern.compile("[0-9a-f]{16}");

    private final SpringDataOutboxRepository outbox;
    private final BulkFileEventEnvelopeFactory envelopes;

    public OutboxBulkFileEventPublisher(SpringDataOutboxRepository outbox, BulkFileEventEnvelopeFactory envelopes) {
        this.outbox = outbox;
        this.envelopes = envelopes;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(BulkFile file, List<BulkFileEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        String fromRequest = MDC.get(InteractionIdFilter.MDC_KEY);
        String correlationId = fromRequest != null ? fromRequest : file.fileId();
        String traceparent = currentTraceparent();
        outbox.saveAll(events.stream().map(event -> {
            OutboxEventJpaEntity row = envelopes.toOutboxRow(event, correlationId);
            row.setTraceparent(traceparent);
            return row;
        }).toList());
    }

    /** W3C traceparent from the trace ids Micrometer Tracing puts in the MDC; null outside a trace. */
    static String currentTraceparent() {
        String traceId = MDC.get("traceId");
        String spanId = MDC.get("spanId");
        if (traceId == null || spanId == null || !TRACE_ID.matcher(traceId).matches()
                || !SPAN_ID.matcher(spanId).matches()) {
            return null;
        }
        return "00-" + traceId + "-" + spanId + "-01";
    }
}
