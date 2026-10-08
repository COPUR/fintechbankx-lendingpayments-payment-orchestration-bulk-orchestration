package com.enterprise.openfinance.bulkpayments.domain.port.out;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkFile;

import java.util.Optional;

public interface BulkFilePort {

    /**
     * Inserts a new file (version 0) or updates a stored one. Fails when the
     * stored version differs from {@link BulkFile#version()} (optimistic lock).
     */
    BulkFile save(BulkFile file);

    Optional<BulkFile> findById(String fileId);

    /**
     * Claims the oldest file still processing for the current transaction.
     * Files claimed by another transaction are skipped, so several replicas
     * can process different files at the same time.
     */
    Optional<BulkFile> claimNextProcessing();
}
