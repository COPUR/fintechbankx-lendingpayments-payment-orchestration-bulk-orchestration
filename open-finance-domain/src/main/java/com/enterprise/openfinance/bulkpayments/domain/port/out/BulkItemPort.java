package com.enterprise.openfinance.bulkpayments.domain.port.out;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkItemResult;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** Items of a bulk file, stored apart from the file and addressed by file id and line number. */
public interface BulkItemPort {

    void saveAll(String fileId, List<BulkItemResult> items);

    /** All items of the file in line order. */
    List<BulkItemResult> findByFileId(String fileId);

    /** At most {@code limit} items not yet processed, in line order. */
    List<BulkItemResult> findUnprocessed(String fileId, int limit);

    /** @return number of items marked */
    int markProcessed(String fileId, Collection<Integer> lineNumbers, Instant processedAt);
}
