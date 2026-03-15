package com.enterprise.openfinance.bulkpayments.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "openfinance.bulkpayments.policy")
public class BulkPaymentsPolicyProperties {

    private Duration idempotencyTtl = Duration.ofHours(24);

    public Duration getIdempotencyTtl() {
        return idempotencyTtl;
    }

    public void setIdempotencyTtl(Duration idempotencyTtl) {
        this.idempotencyTtl = idempotencyTtl;
    }
}
