package com.enterprise.openfinance.bulkpayments.domain.model;

/**
 * Lifecycle of an uploaded file in this service.
 * <ul>
 *   <li>PROCESSING: items are being validated in bounded batches;</li>
 *   <li>VALIDATED: every item is validated and at least one was accepted. The accepted
 *       items have not reached initiation-settlement (no hand-off exists yet), so the
 *       file is neither complete nor final;</li>
 *   <li>REJECTED: every item was rejected; final.</li>
 *   <li>STOPPED: the consent stopped being usable (revoked, expired or gone) before every
 *       item was released, so the remaining items are never released; final.</li>
 * </ul>
 */
public enum BulkFileStatus {
    PROCESSING("Processing", false, false),
    VALIDATED("Validated", true, false),
    REJECTED("Rejected", true, true),
    STOPPED("Stopped", false, true);

    private final String apiValue;
    private final boolean validationFinished;
    private final boolean terminal;

    BulkFileStatus(String apiValue, boolean validationFinished, boolean terminal) {
        this.apiValue = apiValue;
        this.validationFinished = validationFinished;
        this.terminal = terminal;
    }

    public String apiValue() {
        return apiValue;
    }

    /** Every item has been validated. */
    public boolean isValidationFinished() {
        return validationFinished;
    }

    /** The file can no longer change. */
    public boolean isTerminal() {
        return terminal;
    }
}
