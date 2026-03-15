package com.enterprise.openfinance.bulkpayments.infrastructure.rest.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record BulkFileRequest(
        @JsonProperty("Data") Data data
) {

    public record Data(
            @JsonProperty("ConsentId") String consentId,
            @JsonProperty("FileName") String fileName,
            @JsonProperty("FileContent") String fileContent,
            @JsonProperty("FileHash") String fileHash,
            @JsonProperty("IntegrityMode") String integrityMode
    ) {
    }
}
