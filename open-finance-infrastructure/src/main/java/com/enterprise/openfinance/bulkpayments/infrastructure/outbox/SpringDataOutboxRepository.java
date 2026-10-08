package com.enterprise.openfinance.bulkpayments.infrastructure.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataOutboxRepository extends JpaRepository<OutboxEventJpaEntity, UUID> {

    /**
     * Pending rows in insertion order, skipping every row of an aggregate that has a
     * PARKED row: a parked event keeps its file blocked until it is replayed (ADR-021 decision 4).
     */
    @Query(value = """
            select * from outbox_event o
            where o.status = 'PENDING'
              and not exists (select 1 from outbox_event p
                              where p.status = 'PARKED' and p.aggregate_id = o.aggregate_id)
            order by o.created_seq
            limit :batchSize
            """, nativeQuery = true)
    List<OutboxEventJpaEntity> findPendingBatch(@Param("batchSize") int batchSize);

    /** Parked rows not yet counted in outbox_parked_events_total: operator parks done in SQL. */
    @Query("select e from OutboxEventJpaEntity e where e.status = 'PARKED' and e.parkCounted = false order by e.parkedAt")
    List<OutboxEventJpaEntity> findUncountedParks();

    @Modifying
    @Query("delete from OutboxEventJpaEntity e where e.status = 'PUBLISHED' and e.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);

    long countByStatus(String status);

    @Query("select min(e.occurredAt) from OutboxEventJpaEntity e where e.status = 'PENDING'")
    Optional<Instant> findOldestPendingOccurredAt();
}
