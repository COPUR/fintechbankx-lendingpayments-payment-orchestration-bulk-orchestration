package com.enterprise.openfinance.bulkpayments.infrastructure.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Row of sc_pay_bulk_orchestration.outbox_event: one envelope waiting to be
 * relayed to Kafka. Written in the bulk file's transaction.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEventJpaEntity {

    public static final String PENDING = "PENDING";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String PARKED = "PARKED";

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "aggregate_type", nullable = false, length = 64, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64, updatable = false)
    private String aggregateId;

    @Column(name = "aggregate_version", nullable = false, updatable = false)
    private long aggregateVersion;

    @Column(name = "event_type", nullable = false, length = 128, updatable = false)
    private String eventType;

    @Column(name = "topic", nullable = false, length = 249, updatable = false)
    private String topic;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "correlation_id", nullable = false, length = 160, updatable = false)
    private String correlationId;

    /** W3C trace context of the request that raised the event, so traces cross the broker. */
    @Column(name = "traceparent", length = 55, updatable = false)
    private String traceparent;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "status", nullable = false, length = 16)
    private String status = PENDING;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "parked_at")
    private Instant parkedAt;

    /** First failed send; the retryable-failure ceiling is measured from here. */
    @Column(name = "first_failed_at")
    private Instant firstFailedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 512)
    private String lastError;

    protected OutboxEventJpaEntity() {
    }

    public OutboxEventJpaEntity(UUID eventId, String aggregateType, String aggregateId, long aggregateVersion,
                                String eventType, String topic, String payload, String correlationId,
                                Instant occurredAt) {
        this.eventId = eventId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.aggregateVersion = aggregateVersion;
        this.eventType = eventType;
        this.topic = topic;
        this.payload = payload;
        this.correlationId = correlationId;
        this.occurredAt = occurredAt;
    }

    public UUID getEventId() { return eventId; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public long getAggregateVersion() { return aggregateVersion; }
    public String getEventType() { return eventType; }
    public String getTopic() { return topic; }
    public String getPayload() { return payload; }
    public String getCorrelationId() { return correlationId; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getTraceparent() { return traceparent; }

    void setTraceparent(String traceparent) {
        this.traceparent = traceparent;
    }
    public String getStatus() { return status; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getParkedAt() { return parkedAt; }
    public Instant getFirstFailedAt() { return firstFailedAt; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }

    void markPublished(Instant at) {
        this.status = PUBLISHED;
        this.publishedAt = at;
        this.attempts++;
        this.lastError = null;
    }

    /** Records a failed send; the first one starts the retryable-failure clock. */
    void markFailed(String error, Instant at) {
        if (firstFailedAt == null) {
            this.firstFailedAt = at;
        }
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 512));
    }

    /** The relay gave up on this row; it is skipped until an operator replays it. */
    void park(Instant at) {
        this.status = PARKED;
        this.parkedAt = at;
    }
}
