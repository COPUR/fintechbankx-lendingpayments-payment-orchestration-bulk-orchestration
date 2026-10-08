package com.enterprise.openfinance.bulkpayments.domain.port.out;

import com.enterprise.openfinance.bulkpayments.domain.event.BulkFileEvent;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkFile;

import java.util.List;

/**
 * Publishes the file's events. Implementations must write them in the same
 * transaction as the file (transactional outbox).
 */
public interface BulkFileEventPort {

    void publish(BulkFile file, List<BulkFileEvent> events);
}
