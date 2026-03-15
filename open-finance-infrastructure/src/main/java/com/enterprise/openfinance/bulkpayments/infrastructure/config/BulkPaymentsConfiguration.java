package com.enterprise.openfinance.bulkpayments.infrastructure.config;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkSettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties({BulkPaymentsCacheProperties.class, BulkPaymentsPolicyProperties.class, BulkPaymentsProcessingProperties.class})
public class BulkPaymentsConfiguration {

    @Bean
    public Clock bulkPaymentsClock() {
        return Clock.systemUTC();
    }

    @Bean
    public BulkSettings bulkSettings(BulkPaymentsPolicyProperties policyProperties,
                                     BulkPaymentsCacheProperties cacheProperties,
                                     BulkPaymentsProcessingProperties processingProperties) {
        return new BulkSettings(
                policyProperties.getIdempotencyTtl(),
                cacheProperties.getTtl(),
                processingProperties.getMaxFileSizeBytes(),
                processingProperties.getStatusPollsToComplete()
        );
    }
}
