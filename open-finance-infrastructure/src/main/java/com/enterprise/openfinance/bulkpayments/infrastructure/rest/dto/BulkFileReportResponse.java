package com.enterprise.openfinance.bulkpayments.infrastructure.rest.dto;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkFileReport;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkItemResult;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record BulkFileReportResponse(
        @JsonProperty("Data") Data data,
        @JsonProperty("Links") Links links
) {

    public static BulkFileReportResponse from(BulkFileReport report) {
        return new BulkFileReportResponse(
                new Data(
                        report.fileId(),
                        report.status().apiValue(),
                        report.totalCount(),
                        report.acceptedCount(),
                        report.rejectedCount(),
                        report.items().stream().map(Item::from).toList(),
                        report.generatedAt().toString()
                ),
                new Links("/open-finance/v1/file-payments/" + report.fileId() + "/report")
        );
    }

    public record Data(
            @JsonProperty("FilePaymentId") String filePaymentId,
            @JsonProperty("Status") String status,
            @JsonProperty("TotalCount") int totalCount,
            @JsonProperty("AcceptedCount") int acceptedCount,
            @JsonProperty("RejectedCount") int rejectedCount,
            @JsonProperty("Items") List<Item> items,
            @JsonProperty("GeneratedAt") String generatedAt
    ) {
    }

    public record Item(
            @JsonProperty("LineNumber") int lineNumber,
            @JsonProperty("InstructionId") String instructionId,
            @JsonProperty("PayeeIban") String payeeIban,
            @JsonProperty("Amount") String amount,
            @JsonProperty("Status") String status,
            @JsonProperty("ErrorMessage") String errorMessage
    ) {
        public static Item from(BulkItemResult item) {
            return new Item(
                    item.lineNumber(),
                    item.instructionId(),
                    item.payeeIban(),
                    item.amount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
                    item.status().apiValue(),
                    item.errorMessage()
            );
        }
    }

    public record Links(
            @JsonProperty("Self") String self
    ) {
    }
}
