package com.enterprise.openfinance.bulkpayments.domain.port.out;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkFile;

import java.util.List;
import java.util.Optional;

public interface BulkFilePort {

    /**
     * Inserts a new file (version 0) or updates a stored one. Fails when the
     * stored version differs from {@link BulkFile#version()} (optimistic lock).
     */
    BulkFile save(BulkFile file);

    Optional<BulkFile> findById(String fileId);

    /**
     * Up to {@code limit} files still processing, oldest first, read without a
     * lock: the candidates of a processing run.
     */
    List<BulkFile> findProcessing(int limit);

    /**
     * Claims {@code fileId} for the current transaction if it is still
     * processing. A file claimed by another transaction is skipped (empty), not
     * waited for, so several replicas can process different files at once.
     */
    Optional<BulkFile> claimProcessing(String fileId);
}
