package com.enterprise.openfinance.bulkpayments.infrastructure.persistence;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkFile;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkFilePort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class JpaBulkFileAdapter implements BulkFilePort {

    private final SpringDataBulkFileRepository files;

    public JpaBulkFileAdapter(SpringDataBulkFileRepository files) {
        this.files = files;
    }

    @Override
    public BulkFile save(BulkFile file) {
        Optional<BulkFileJpaEntity> stored = files.findById(file.fileId());
        if (stored.isEmpty()) {
            // Flushed at once so the item rows (plain JDBC) can reference it.
            files.saveAndFlush(BulkFilePersistenceMapper.toNewEntity(file));
            return file;
        }
        BulkFileJpaEntity entity = stored.orElseThrow();
        if (entity.getVersion() != file.version()) {
            throw new ObjectOptimisticLockingFailureException(BulkFileJpaEntity.class, file.fileId());
        }
        BulkFilePersistenceMapper.copyProgress(file, entity);
        files.saveAndFlush(entity);
        return file;
    }

    @Override
    public Optional<BulkFile> findById(String fileId) {
        return files.findById(fileId).map(BulkFilePersistenceMapper::toDomain);
    }

    @Override
    public Optional<BulkFile> claimNextProcessing() {
        return files.lockNextProcessing().map(BulkFilePersistenceMapper::toDomain);
    }
}
