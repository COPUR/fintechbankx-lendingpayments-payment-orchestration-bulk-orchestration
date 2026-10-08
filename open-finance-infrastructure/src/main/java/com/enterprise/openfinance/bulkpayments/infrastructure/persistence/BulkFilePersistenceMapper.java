package com.enterprise.openfinance.bulkpayments.infrastructure.persistence;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkFile;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkFileStatus;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkIntegrityMode;
import com.enterprise.openfinance.bulkpayments.domain.model.Money;

import java.util.Currency;

/** Maps the BulkFile aggregate to its table row and back; the domain never sees JPA types. */
public final class BulkFilePersistenceMapper {

    private BulkFilePersistenceMapper() {
    }

    public static BulkFileJpaEntity toNewEntity(BulkFile file) {
        BulkFileJpaEntity entity = new BulkFileJpaEntity(file.fileId(), file.consentId(), file.tppId(),
                file.idempotencyKey(), file.requestHash(), file.fileName(), file.integrityMode().name(),
                file.targetStatus().name(), file.totalCount(), file.acceptedCount(), file.rejectedCount(),
                file.totalAmount().amount(), file.acceptedAmount().amount(), file.currency().getCurrencyCode(),
                file.createdAt());
        copyProgress(file, entity);
        return entity;
    }

    public static void copyProgress(BulkFile file, BulkFileJpaEntity entity) {
        entity.applyProgress(file.status().name(), file.processedCount(), file.processedAt());
    }

    public static BulkFile toDomain(BulkFileJpaEntity entity) {
        return BulkFile.rehydrate(entity.getFileId(), entity.getConsentId(), entity.getTppId(),
                entity.getIdempotencyKey(), entity.getRequestHash(), entity.getFileName(),
                BulkIntegrityMode.valueOf(entity.getIntegrityMode()), BulkFileStatus.valueOf(entity.getStatus()),
                BulkFileStatus.valueOf(entity.getTargetStatus()), entity.getProcessedCount(),
                entity.getTotalCount(), entity.getAcceptedCount(), entity.getRejectedCount(),
                new Money(entity.getTotalAmount(), currency(entity)),
                new Money(entity.getAcceptedAmount(), currency(entity)), entity.getCreatedAt(), entity.getProcessedAt(),
                entity.getVersion());
    }

    private static Currency currency(BulkFileJpaEntity entity) {
        return Currency.getInstance(entity.getCurrency());
    }
}
