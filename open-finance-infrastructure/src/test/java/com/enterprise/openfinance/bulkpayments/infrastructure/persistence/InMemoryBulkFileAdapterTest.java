package com.enterprise.openfinance.bulkpayments.infrastructure.persistence;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkFile;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkFileStatus;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkIntegrityMode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class InMemoryBulkFileAdapterTest {

    @Test
    void shouldSaveAndFindFile() {
        InMemoryBulkFileAdapter adapter = new InMemoryBulkFileAdapter();

        BulkFile file = new BulkFile(
                "FILE-1",
                "CONS-1",
                "TPP-001",
                "IDEMP-1",
                "hash-1",
                "payroll.csv",
                BulkIntegrityMode.PARTIAL_REJECTION,
                BulkFileStatus.PROCESSING,
                BulkFileStatus.COMPLETED,
                0,
                1,
                1,
                0,
                new BigDecimal("10.00"),
                Instant.parse("2026-02-09T10:00:00Z"),
                null
        );

        adapter.save(file);

        assertThat(adapter.findById("FILE-1")).isPresent();
    }
}
