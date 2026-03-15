package com.enterprise.openfinance.bulkpayments.infrastructure.rest.dto;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkUploadResult;
import com.fasterxml.jackson.annotation.JsonProperty;

public record BulkUploadResponse(
        @JsonProperty("Data") Data data,
        @JsonProperty("Links") Links links
) {

    public static BulkUploadResponse from(BulkUploadResult result) {
        return new BulkUploadResponse(
                new Data(
                        result.fileId(),
                        result.status().apiValue(),
                        result.acceptedCount(),
                        result.rejectedCount(),
                        result.createdAt().toString()
                ),
                new Links("/open-finance/v1/file-payments/" + result.fileId())
        );
    }

    public record Data(
            @JsonProperty("FilePaymentId") String filePaymentId,
            @JsonProperty("Status") String status,
            @JsonProperty("AcceptedCount") int acceptedCount,
            @JsonProperty("RejectedCount") int rejectedCount,
            @JsonProperty("CreationDateTime") String creationDateTime
    ) {
    }

    public record Links(
            @JsonProperty("Self") String self
    ) {
    }
}
