package com.enterprise.openfinance.bulkpayments.domain.model;

public enum BulkFileStatus {
    PROCESSING("Processing", false),
    COMPLETED("Completed", true),
    PARTIALLY_ACCEPTED("PartiallyAccepted", true),
    REJECTED("Rejected", true);

    private final String apiValue;
    private final boolean terminal;

    BulkFileStatus(String apiValue, boolean terminal) {
        this.apiValue = apiValue;
        this.terminal = terminal;
    }

    public String apiValue() {
        return apiValue;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
