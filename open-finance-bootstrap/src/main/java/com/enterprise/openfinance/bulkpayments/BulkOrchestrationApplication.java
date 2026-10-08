package com.enterprise.openfinance.bulkpayments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * svc-pay-bulk-orchestration: bulk payment files and their items, extracted
 * from the open-finance-context bulkpayments capability of
 * enterprise-loan-management-system.
 */
@SpringBootApplication
public class BulkOrchestrationApplication {

    public static void main(String[] args) {
        SpringApplication.run(BulkOrchestrationApplication.class, args);
    }
}
