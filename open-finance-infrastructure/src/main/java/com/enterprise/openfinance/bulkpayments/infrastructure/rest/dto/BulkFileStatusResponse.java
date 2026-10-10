package com.enterprise.openfinance.bulkpayments.infrastructure.rest.dto;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkFile;
import com.fasterxml.jackson.annotation.JsonProperty;

public record BulkFileStatusResponse(
        @JsonProperty("Data") Data data,
        @JsonProperty("Links") Links links
) {

    public static BulkFileStatusResponse from(BulkFile file) {
        return new BulkFileStatusResponse(
                new Data(
                        file.fileId(),
                        file.status().apiValue(),
                        file.totalCount(),
                        // What the file stands by: a Stopped file reports 0 accepted, like its report and event.
                        file.releasedAcceptedCount(),
                        file.releasedRejectedCount(),
                        file.createdAt().toString(),
                        file.processedAt() == null ? null : file.processedAt().toString()
                ),
                new Links("/open-finance/v1/file-payments/" + file.fileId())
        );
    }

    public record Data(
            @JsonProperty("FilePaymentId") String filePaymentId,
            @JsonProperty("Status") String status,
            @JsonProperty("TotalCount") int totalCount,
            @JsonProperty("AcceptedCount") int acceptedCount,
            @JsonProperty("RejectedCount") int rejectedCount,
            @JsonProperty("CreationDateTime") String creationDateTime,
            @JsonProperty("ProcessedDateTime") String processedDateTime
    ) {
    }

    public record Links(
            @JsonProperty("Self") String self
    ) {
    }
}
