package com.enterprise.openfinance.bulkpayments.infrastructure.persistence;

import com.enterprise.openfinance.bulkpayments.domain.model.Money;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkFile;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkFileStatus;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkIntegrityMode;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkItemResult;
import com.enterprise.openfinance.bulkpayments.domain.model.ParsedBulkFile;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BulkFilePersistenceMapperTest {

    static final Instant AT = Instant.parse("2026-02-09T10:00:00Z");

    @Test
    void roundTripsEveryField() {
        BulkFile file = newFile();
        file.recordProcessedBatch(1, AT.plusSeconds(1));

        BulkFileJpaEntity entity = BulkFilePersistenceMapper.toNewEntity(file);
        BulkFile back = BulkFilePersistenceMapper.toDomain(entity);

        assertThat(entity.getStatus()).isEqualTo("PROCESSING");
        assertThat(entity.getTargetStatus()).isEqualTo("VALIDATED");
        assertThat(entity.getIntegrityMode()).isEqualTo("FULL_REJECTION");
        assertThat(back.fileId()).isEqualTo("FILE-1");
        assertThat(back.consentId()).isEqualTo("CONS-1");
        assertThat(back.tppId()).isEqualTo("TPP-001");
        assertThat(back.idempotencyKey()).isEqualTo("IDEMP-1");
        assertThat(back.requestHash()).isEqualTo("hash");
        assertThat(back.fileName()).isEqualTo("payroll.csv");
        assertThat(back.integrityMode()).isEqualTo(BulkIntegrityMode.FULL_REJECTION);
        assertThat(back.status()).isEqualTo(BulkFileStatus.PROCESSING);
        assertThat(back.processedCount()).isEqualTo(1);
        assertThat(back.totalCount()).isEqualTo(2);
        assertThat(back.acceptedCount()).isEqualTo(1);
        assertThat(back.rejectedCount()).isEqualTo(1);
        assertThat(entity.getCurrency()).isEqualTo("AED");
        assertThat(back.totalAmount()).isEqualTo(Money.of("30.00", "AED"));
        assertThat(back.acceptedAmount()).isEqualTo(Money.of("10.00", "AED"));
        assertThat(entity.getConsentExpiresAt()).isEqualTo(AT.plusSeconds(86_400));
        assertThat(back.consentExpiresAt()).isEqualTo(AT.plusSeconds(86_400));
        assertThat(back.createdAt()).isEqualTo(AT);
        assertThat(back.processedAt()).isNull();
        assertThat(back.version()).isZero();
        assertThat(back.pullDomainEvents()).isEmpty();
    }

    @Test
    void copyProgressMovesOnlyTheMutableFields() {
        BulkFile file = newFile();
        BulkFileJpaEntity entity = BulkFilePersistenceMapper.toNewEntity(file);
        file.recordProcessedBatch(2, AT.plusSeconds(5));

        BulkFilePersistenceMapper.copyProgress(file, entity);

        assertThat(entity.getStatus()).isEqualTo("VALIDATED");
        assertThat(entity.getProcessedCount()).isEqualTo(2);
        assertThat(entity.getProcessedAt()).isEqualTo(AT.plusSeconds(5));
        assertThat(entity.getTotalCount()).isEqualTo(2);
    }

    static BulkFile newFile() {
        ParsedBulkFile parsed = new ParsedBulkFile(List.of(
                BulkItemResult.accepted(1, "INS-1", "AE120001000000000000000001", Money.of("10.00", "AED")),
                BulkItemResult.rejected(2, "INS-2", "AE000", Money.of("20.00", "AED"), "Invalid IBAN")),
                2, 1, 1, Money.of("30.00", "AED"), Money.of("10.00", "AED"), BulkFileStatus.VALIDATED);
        return BulkFile.accept("FILE-1", "CONS-1", "TPP-001", "IDEMP-1", "hash", "payroll.csv",
                BulkIntegrityMode.FULL_REJECTION, parsed, AT.plusSeconds(86_400), AT);
    }
}
