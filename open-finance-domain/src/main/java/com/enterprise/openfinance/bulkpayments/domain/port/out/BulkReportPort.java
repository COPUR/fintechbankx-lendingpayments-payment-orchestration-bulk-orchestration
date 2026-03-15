package com.enterprise.openfinance.bulkpayments.domain.port.out;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkFileReport;

import java.util.Optional;

public interface BulkReportPort {
    BulkFileReport save(BulkFileReport report);

    Optional<BulkFileReport> findByFileId(String fileId);
}
