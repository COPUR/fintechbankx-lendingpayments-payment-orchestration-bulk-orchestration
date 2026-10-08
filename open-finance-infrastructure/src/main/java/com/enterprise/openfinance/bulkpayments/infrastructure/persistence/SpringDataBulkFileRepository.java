package com.enterprise.openfinance.bulkpayments.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SpringDataBulkFileRepository extends JpaRepository<BulkFileJpaEntity, String> {

    /** The oldest processing files, without a lock (candidates of a processing run). */
    @Query(value = """
            select * from bulk_file
            where status = 'PROCESSING'
            order by created_at, file_id
            limit :limit
            """, nativeQuery = true)
    List<BulkFileJpaEntity> findProcessing(@Param("limit") int limit);

    /**
     * Locks the file for the current transaction if it is still processing; a
     * row locked by another replica is skipped instead of waited for.
     */
    @Query(value = """
            select * from bulk_file
            where file_id = :fileId and status = 'PROCESSING'
            for update skip locked
            """, nativeQuery = true)
    Optional<BulkFileJpaEntity> lockProcessing(@Param("fileId") String fileId);
}
