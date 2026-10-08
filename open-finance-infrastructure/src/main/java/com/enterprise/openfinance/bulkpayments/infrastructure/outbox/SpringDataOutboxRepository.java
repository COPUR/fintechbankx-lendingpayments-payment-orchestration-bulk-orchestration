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
     * Takes the cluster-wide relay lock for the current transaction. Only one
     * replica relays at a time, which keeps each file's events in order.
     */
    @Query(value = "select pg_try_advisory_xact_lock(:key)", nativeQuery = true)
    boolean tryRelayLock(@Param("key") long key);

    @Query(value = """
            select * from outbox_event
            where status = 'PENDING'
            order by created_seq
            limit :batchSize
            """, nativeQuery = true)
    List<OutboxEventJpaEntity> findPendingBatch(@Param("batchSize") int batchSize);

    @Modifying
    @Query("delete from OutboxEventJpaEntity e where e.status = 'PUBLISHED' and e.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);

    long countByStatus(String status);

    @Query("select min(e.occurredAt) from OutboxEventJpaEntity e where e.status = 'PENDING'")
    Optional<Instant> findOldestPendingOccurredAt();
}
