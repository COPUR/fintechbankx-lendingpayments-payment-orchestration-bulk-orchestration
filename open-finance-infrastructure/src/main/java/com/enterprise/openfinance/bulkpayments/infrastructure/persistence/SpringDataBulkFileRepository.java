package com.enterprise.openfinance.bulkpayments.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface SpringDataBulkFileRepository extends JpaRepository<BulkFileJpaEntity, String> {

    /**
     * Locks the oldest processing file for the current transaction; rows
     * locked by another replica are skipped instead of waited for.
     */
    @Query(value = """
            select * from bulk_file
            where status = 'PROCESSING'
            order by created_at, file_id
            limit 1
            for update skip locked
            """, nativeQuery = true)
    Optional<BulkFileJpaEntity> lockNextProcessing();
}
