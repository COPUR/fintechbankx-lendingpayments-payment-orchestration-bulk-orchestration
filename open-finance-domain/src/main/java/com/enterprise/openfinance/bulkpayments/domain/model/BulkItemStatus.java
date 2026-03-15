package com.enterprise.openfinance.bulkpayments.domain.model;

public enum BulkItemStatus {
    ACCEPTED("Accepted"),
    REJECTED("Rejected");

    private final String apiValue;

    BulkItemStatus(String apiValue) {
        this.apiValue = apiValue;
    }

    public String apiValue() {
        return apiValue;
    }
}
