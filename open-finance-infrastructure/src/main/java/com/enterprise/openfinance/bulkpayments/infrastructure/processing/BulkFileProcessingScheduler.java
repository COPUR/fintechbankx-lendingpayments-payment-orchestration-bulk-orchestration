package com.enterprise.openfinance.bulkpayments.infrastructure.processing;

import com.enterprise.openfinance.bulkpayments.domain.port.in.ProcessBulkFilesUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives {@link ProcessBulkFilesUseCase}: each run processes up to
 * {@code maxBatchesPerRun} batches, each in its own transaction, and stops
 * early when no file is waiting. Every replica runs it; row locks with SKIP
 * LOCKED keep two replicas off the same file.
 */
public class BulkFileProcessingScheduler {

    private static final Logger log = LoggerFactory.getLogger(BulkFileProcessingScheduler.class);

    private final ProcessBulkFilesUseCase processor;
    private final int maxBatchesPerRun;

    public BulkFileProcessingScheduler(ProcessBulkFilesUseCase processor, int maxBatchesPerRun) {
        if (maxBatchesPerRun <= 0) {
            throw new IllegalArgumentException("maxBatchesPerRun must be positive");
        }
        this.processor = processor;
        this.maxBatchesPerRun = maxBatchesPerRun;
    }

    /** @return items processed in this run */
    public int runOnce() {
        int items = 0;
        for (int batch = 0; batch < maxBatchesPerRun; batch++) {
            int processed;
            try {
                processed = processor.processNextBatch();
            } catch (RuntimeException exception) {
                // The batch rolled back; the next run retries it. Do not hot-loop on it now.
                log.error("Bulk file batch failed and was rolled back; retrying on the next run", exception);
                break;
            }
            if (processed == 0) {
                break;
            }
            items += processed;
        }
        return items;
    }
}
