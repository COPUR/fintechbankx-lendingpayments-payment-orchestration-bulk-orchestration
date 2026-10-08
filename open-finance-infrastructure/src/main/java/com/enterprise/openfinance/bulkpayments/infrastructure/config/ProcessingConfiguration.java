package com.enterprise.openfinance.bulkpayments.infrastructure.config;

import com.enterprise.openfinance.bulkpayments.domain.port.in.ProcessBulkFilesUseCase;
import com.enterprise.openfinance.bulkpayments.infrastructure.processing.BulkFileProcessingScheduler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Background processing of uploaded files; disable with openfinance.bulkpayments.processing.enabled=false. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "openfinance.bulkpayments.processing.enabled", havingValue = "true", matchIfMissing = true)
public class ProcessingConfiguration {

    @Bean
    BulkFileProcessingScheduler bulkFileProcessingScheduler(ProcessBulkFilesUseCase processor,
                                                            BulkPaymentsProcessingProperties properties) {
        return new BulkFileProcessingScheduler(processor, properties.getMaxBatchesPerRun());
    }

    @Bean
    ProcessingSchedule processingSchedule(BulkFileProcessingScheduler scheduler) {
        return new ProcessingSchedule(scheduler);
    }

    static class ProcessingSchedule {
        private final BulkFileProcessingScheduler scheduler;

        ProcessingSchedule(BulkFileProcessingScheduler scheduler) {
            this.scheduler = scheduler;
        }

        @Scheduled(fixedDelayString = "${openfinance.bulkpayments.processing.interval:PT1S}")
        void process() {
            scheduler.runOnce();
        }
    }
}
