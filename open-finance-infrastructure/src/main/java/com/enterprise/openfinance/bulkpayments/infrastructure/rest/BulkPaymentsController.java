package com.enterprise.openfinance.bulkpayments.infrastructure.rest;

import com.enterprise.openfinance.bulkpayments.domain.command.SubmitBulkFileCommand;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkFile;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkFileReport;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkIntegrityMode;
import com.enterprise.openfinance.bulkpayments.domain.port.in.BulkPaymentUseCase;
import com.enterprise.openfinance.bulkpayments.domain.query.GetBulkFileReportQuery;
import com.enterprise.openfinance.bulkpayments.domain.query.GetBulkFileStatusQuery;
import com.enterprise.openfinance.bulkpayments.infrastructure.rest.dto.BulkFileReportResponse;
import com.enterprise.openfinance.bulkpayments.infrastructure.rest.dto.BulkFileRequest;
import com.enterprise.openfinance.bulkpayments.infrastructure.rest.dto.BulkFileStatusResponse;
import com.enterprise.openfinance.bulkpayments.infrastructure.rest.dto.BulkUploadResponse;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

@RestController
@Validated
@RequestMapping("/open-finance/v1/file-payments")
public class BulkPaymentsController {

    private final BulkPaymentUseCase useCase;

    public BulkPaymentsController(BulkPaymentUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping
    public ResponseEntity<BulkUploadResponse> uploadFile(
            @RequestHeader("Authorization") @NotBlank String authorization,
            @RequestHeader("DPoP") @NotBlank String dpop,
            @RequestHeader("X-FAPI-Interaction-ID") @NotBlank String interactionId,
            @RequestHeader(value = "x-fapi-financial-id", required = false) String financialId,
            @RequestHeader("x-idempotency-key") @NotBlank String idempotencyKey,
            @RequestBody BulkFileRequest request
    ) {
        validateSecurityHeaders(authorization, dpop, interactionId);

        BulkFileRequest.Data data = requireData(request);
        String tppId = resolveTppId(financialId);

        var command = new SubmitBulkFileCommand(
                tppId,
                data.consentId(),
                idempotencyKey,
                data.fileName(),
                data.fileContent(),
                data.fileHash(),
                BulkIntegrityMode.fromApiValue(data.integrityMode()),
                interactionId
        );

        var result = useCase.submitFile(command);
        BulkUploadResponse response = BulkUploadResponse.from(result);

        return ResponseEntity.accepted()
                .location(URI.create("/open-finance/v1/file-payments/" + result.fileId()))
                .cacheControl(CacheControl.maxAge(0, TimeUnit.SECONDS).noStore())
                .header("X-FAPI-Interaction-ID", interactionId)
                .header("X-Idempotency-Key", idempotencyKey)
                .header("X-OF-Idempotency", result.idempotencyReplay() ? "HIT" : "MISS")
                .body(response);
    }

    @GetMapping("/{fileId}")
    public ResponseEntity<BulkFileStatusResponse> getFileStatus(
            @RequestHeader("Authorization") @NotBlank String authorization,
            @RequestHeader("DPoP") @NotBlank String dpop,
            @RequestHeader("X-FAPI-Interaction-ID") @NotBlank String interactionId,
            @RequestHeader(value = "x-fapi-financial-id", required = false) String financialId,
            @PathVariable @NotBlank String fileId,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch
    ) {
        validateSecurityHeaders(authorization, dpop, interactionId);
        String tppId = resolveTppId(financialId);

        var file = useCase.getFileStatus(new GetBulkFileStatusQuery(fileId, tppId, interactionId));
        if (file.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .cacheControl(CacheControl.maxAge(0, TimeUnit.SECONDS).noStore())
                    .header("X-FAPI-Interaction-ID", interactionId)
                    .build();
        }

        BulkFileStatusResponse response = BulkFileStatusResponse.from(file.orElseThrow());
        String etag = generateStatusEtag(file.orElseThrow());

        if (ifNoneMatch != null && ifNoneMatch.equals(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .cacheControl(CacheControl.maxAge(0, TimeUnit.SECONDS).noStore())
                    .header("X-FAPI-Interaction-ID", interactionId)
                    .eTag(etag)
                    .build();
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(0, TimeUnit.SECONDS).noStore())
                .header("X-FAPI-Interaction-ID", interactionId)
                .eTag(etag)
                .body(response);
    }

    @GetMapping("/{fileId}/report")
    public ResponseEntity<BulkFileReportResponse> getFileReport(
            @RequestHeader("Authorization") @NotBlank String authorization,
            @RequestHeader("DPoP") @NotBlank String dpop,
            @RequestHeader("X-FAPI-Interaction-ID") @NotBlank String interactionId,
            @RequestHeader(value = "x-fapi-financial-id", required = false) String financialId,
            @PathVariable @NotBlank String fileId,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch
    ) {
        validateSecurityHeaders(authorization, dpop, interactionId);
        String tppId = resolveTppId(financialId);

        var report = useCase.getFileReport(new GetBulkFileReportQuery(fileId, tppId, interactionId));
        if (report.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .cacheControl(CacheControl.maxAge(0, TimeUnit.SECONDS).noStore())
                    .header("X-FAPI-Interaction-ID", interactionId)
                    .build();
        }

        BulkFileReportResponse response = BulkFileReportResponse.from(report.orElseThrow());
        String etag = generateReportEtag(report.orElseThrow());

        if (ifNoneMatch != null && ifNoneMatch.equals(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .cacheControl(CacheControl.maxAge(0, TimeUnit.SECONDS).noStore())
                    .header("X-FAPI-Interaction-ID", interactionId)
                    .eTag(etag)
                    .build();
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(0, TimeUnit.SECONDS).noStore())
                .header("X-FAPI-Interaction-ID", interactionId)
                .eTag(etag)
                .body(response);
    }

    private static BulkFileRequest.Data requireData(BulkFileRequest request) {
        if (request == null || request.data() == null) {
            throw new IllegalArgumentException("Request Data is required");
        }
        return request.data();
    }

    private static String resolveTppId(String financialId) {
        if (financialId == null || financialId.isBlank()) {
            return "UNKNOWN_TPP";
        }
        return financialId.trim();
    }

    private static void validateSecurityHeaders(String authorization,
                                                String dpop,
                                                String interactionId) {
        boolean validAuthorization = authorization.startsWith("DPoP ") || authorization.startsWith("Bearer ");
        if (!validAuthorization) {
            throw new IllegalArgumentException("Authorization header must use Bearer or DPoP token type");
        }
        if (dpop.isBlank()) {
            throw new IllegalArgumentException("DPoP header is required");
        }
        if (interactionId.isBlank()) {
            throw new IllegalArgumentException("X-FAPI-Interaction-ID header is required");
        }
    }

    private static String generateStatusEtag(BulkFile file) {
        String signature = file.fileId() + '|' + file.status() + '|' + file.pollCount() + '|' + file.processedAt();
        return hashSignature(signature);
    }

    private static String generateReportEtag(BulkFileReport report) {
        String signature = report.fileId() + '|' + report.status() + '|' + report.acceptedCount() + '|' + report.rejectedCount();
        return hashSignature(signature);
    }

    private static String hashSignature(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return '"' + Base64.getUrlEncoder().withoutPadding().encodeToString(hash) + '"';
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to generate ETag", exception);
        }
    }
}
