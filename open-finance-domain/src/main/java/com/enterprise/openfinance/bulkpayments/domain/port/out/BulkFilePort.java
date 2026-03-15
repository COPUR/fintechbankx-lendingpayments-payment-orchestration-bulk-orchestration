package com.enterprise.openfinance.bulkpayments.domain.port.out;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkFile;

import java.util.Optional;

public interface BulkFilePort {
    BulkFile save(BulkFile file);

    Optional<BulkFile> findById(String fileId);
}
