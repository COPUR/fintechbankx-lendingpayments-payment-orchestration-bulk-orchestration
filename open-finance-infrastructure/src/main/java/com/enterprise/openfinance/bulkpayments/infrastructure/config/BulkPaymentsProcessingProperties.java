package com.enterprise.openfinance.bulkpayments.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openfinance.bulkpayments.processing")
public class BulkPaymentsProcessingProperties {

    private long maxFileSizeBytes = 10_000_000L;
    private int statusPollsToComplete = 2;

    public long getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    public void setMaxFileSizeBytes(long maxFileSizeBytes) {
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public int getStatusPollsToComplete() {
        return statusPollsToComplete;
    }

    public void setStatusPollsToComplete(int statusPollsToComplete) {
        this.statusPollsToComplete = statusPollsToComplete;
    }
}
