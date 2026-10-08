package com.enterprise.openfinance.bulkpayments.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * The report of a file: its counts and every item with its outcome.
 */
public record BulkFileReport(
        String fileId,
        BulkFileStatus status,
        int totalCount,
        int acceptedCount,
        int rejectedCount,
        List<BulkItemResult> items,
        Instant generatedAt
) {

    public BulkFileReport {
        if (isBlank(fileId)) {
            throw new IllegalArgumentException("fileId is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (totalCount <= 0) {
            throw new IllegalArgumentException("totalCount must be > 0");
        }
        if (acceptedCount < 0 || acceptedCount > totalCount) {
            throw new IllegalArgumentException("acceptedCount out of range");
        }
        if (rejectedCount < 0 || rejectedCount > totalCount) {
            throw new IllegalArgumentException("rejectedCount out of range");
        }
        if (acceptedCount + rejectedCount > totalCount) {
            throw new IllegalArgumentException("acceptedCount + rejectedCount must be <= totalCount");
        }
        if (items == null) {
            throw new IllegalArgumentException("items are required");
        }
        if (generatedAt == null) {
            throw new IllegalArgumentException("generatedAt is required");
        }

        fileId = fileId.trim();
        items = List.copyOf(items);
    }

    /** Error message of the items of a STOPPED file that were accepted at validation. */
    public static final String CONSENT_NOT_USABLE = "Consent not usable";

    /**
     * Builds the report of {@code file} from its stored items. A STOPPED file
     * releases nothing: its Rejected event (reason CONSENT_NOT_USABLE) counts
     * every item as rejected, so the report shows every item Rejected (accepted
     * ones with {@link #CONSENT_NOT_USABLE}, rejected ones with their own
     * reason) and AcceptedCount 0. Other files report their items as stored.
     */
    public static BulkFileReport of(BulkFile file, List<BulkItemResult> items, Instant generatedAt) {
        if (file.status() != BulkFileStatus.STOPPED) {
            return new BulkFileReport(file.fileId(), file.status(), file.totalCount(), file.acceptedCount(),
                    file.rejectedCount(), items, generatedAt);
        }
        List<BulkItemResult> notReleased = items.stream()
                .map(item -> item.status() == BulkItemStatus.ACCEPTED
                        ? BulkItemResult.rejected(item.lineNumber(), item.instructionId(), item.payeeIban(),
                        item.amount(), CONSENT_NOT_USABLE)
                        : item)
                .toList();
        return new BulkFileReport(file.fileId(), file.status(), file.totalCount(), 0, file.totalCount(),
                notReleased, generatedAt);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
