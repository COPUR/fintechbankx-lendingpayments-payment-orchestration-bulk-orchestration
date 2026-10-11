package com.enterprise.openfinance.bulkpayments.domain.model;

import java.util.List;

/**
 * Result of parsing and validating an uploaded file: every item with its
 * validation outcome, the counts and amounts, and the status the file reaches
 * once all items are processed.
 */
public record ParsedBulkFile(
        List<BulkItemResult> items,
        int totalCount,
        int acceptedCount,
        int rejectedCount,
        Money totalAmount,
        Money acceptedAmount,
        BulkFileStatus targetStatus
) {

    public ParsedBulkFile {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("items are required");
        }
        if (totalCount != items.size()) {
            throw new IllegalArgumentException("totalCount must equal the number of items");
        }
        if (acceptedCount < 0 || rejectedCount < 0 || acceptedCount + rejectedCount != totalCount) {
            throw new IllegalArgumentException("acceptedCount + rejectedCount must equal totalCount");
        }
        if (totalAmount == null || totalAmount.signum() <= 0) {
            throw new IllegalArgumentException("totalAmount must be positive");
        }
        if (acceptedAmount == null || !acceptedAmount.currency().equals(totalAmount.currency())
                || items.stream().anyMatch(item -> !item.amount().currency().equals(totalAmount.currency()))) {
            throw new IllegalArgumentException("items and totals must share one currency");
        }
        if (acceptedAmount.signum() < 0 || acceptedAmount.isGreaterThan(totalAmount)) {
            throw new IllegalArgumentException("acceptedAmount must be between zero and totalAmount");
        }
        if (targetStatus == null || !targetStatus.isValidationFinished()) {
            throw new IllegalArgumentException("targetStatus must be VALIDATED or REJECTED");
        }
        items = List.copyOf(items);
    }
}
