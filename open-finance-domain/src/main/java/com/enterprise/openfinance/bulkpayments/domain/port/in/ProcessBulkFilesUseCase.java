package com.enterprise.openfinance.bulkpayments.domain.port.in;

public interface ProcessBulkFilesUseCase {

    /**
     * Processes one bounded batch of items of the oldest file still
     * processing, in its own transaction.
     *
     * @return number of items processed; 0 when no file is waiting
     */
    int processNextBatch();
}
