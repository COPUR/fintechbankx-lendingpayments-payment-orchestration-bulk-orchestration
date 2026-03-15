package com.enterprise.openfinance.bulkpayments.infrastructure.persistence;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkFile;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkFilePort;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryBulkFileAdapter implements BulkFilePort {

    private final Map<String, BulkFile> data = new ConcurrentHashMap<>();

    @Override
    public BulkFile save(BulkFile file) {
        data.put(file.fileId(), file);
        return file;
    }

    @Override
    public Optional<BulkFile> findById(String fileId) {
        return Optional.ofNullable(data.get(fileId));
    }
}
