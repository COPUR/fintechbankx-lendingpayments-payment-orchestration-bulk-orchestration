package com.enterprise.openfinance.bulkpayments.infrastructure.persistence;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkFileReport;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkFileStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class InMemoryBulkReportAdapterTest {

    @Test
    void shouldSaveAndFindReport() {
        InMemoryBulkReportAdapter adapter = new InMemoryBulkReportAdapter();

        BulkFileReport report = new BulkFileReport(
                "FILE-1",
                BulkFileStatus.COMPLETED,
                1,
                1,
                0,
                List.of(),
                Instant.parse("2026-02-09T10:00:00Z")
        );

        adapter.save(report);

        assertThat(adapter.findByFileId("FILE-1")).isPresent();
    }
}
