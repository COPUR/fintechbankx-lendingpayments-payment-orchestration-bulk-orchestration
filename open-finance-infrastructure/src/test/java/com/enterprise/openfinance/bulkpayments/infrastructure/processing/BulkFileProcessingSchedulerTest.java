package com.enterprise.openfinance.bulkpayments.infrastructure.processing;

import com.enterprise.openfinance.bulkpayments.domain.port.in.ProcessBulkFilesUseCase;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BulkFileProcessingSchedulerTest {

    @Test
    void drainsBatchesUntilNoFileIsWaiting() {
        Scripted processor = new Scripted(500, 500, 120, 0, 999);

        assertThat(new BulkFileProcessingScheduler(processor, 10).runOnce()).isEqualTo(1120);
        assertThat(processor.calls).isEqualTo(4);
    }

    @Test
    void stopsAfterMaxBatchesPerRun() {
        Scripted processor = new Scripted(500, 500, 500, 500);

        assertThat(new BulkFileProcessingScheduler(processor, 2).runOnce()).isEqualTo(1000);
        assertThat(processor.calls).isEqualTo(2);
    }

    @Test
    void failedBatchEndsTheRunWithoutPropagating() {
        ProcessBulkFilesUseCase failing = () -> {
            throw new IllegalStateException("db down");
        };

        assertThat(new BulkFileProcessingScheduler(failing, 5).runOnce()).isZero();
        assertThatThrownBy(() -> new BulkFileProcessingScheduler(failing, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static final class Scripted implements ProcessBulkFilesUseCase {
        private final ArrayDeque<Integer> results;
        private int calls;

        Scripted(Integer... results) {
            this.results = new ArrayDeque<>(List.of(results));
        }

        @Override
        public int processNextBatch() {
            calls++;
            return results.isEmpty() ? 0 : results.poll();
        }
    }
}
