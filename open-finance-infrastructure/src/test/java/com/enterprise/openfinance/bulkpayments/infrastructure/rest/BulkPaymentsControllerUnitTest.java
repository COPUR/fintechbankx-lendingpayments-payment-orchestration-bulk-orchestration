package com.enterprise.openfinance.bulkpayments.infrastructure.rest;

import com.enterprise.openfinance.bulkpayments.domain.command.SubmitBulkFileCommand;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkFile;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkFileReport;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkFileStatus;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkIntegrityMode;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkItemResult;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkUploadResult;
import com.enterprise.openfinance.bulkpayments.domain.port.in.BulkPaymentUseCase;
import com.enterprise.openfinance.bulkpayments.infrastructure.rest.dto.BulkFileReportResponse;
import com.enterprise.openfinance.bulkpayments.infrastructure.rest.dto.BulkFileRequest;
import com.enterprise.openfinance.bulkpayments.infrastructure.rest.dto.BulkFileStatusResponse;
import com.enterprise.openfinance.bulkpayments.infrastructure.rest.dto.BulkUploadResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class BulkPaymentsControllerUnitTest {

    @Test
    void shouldUploadAndRetrieveStatusAndReport() {
        BulkPaymentUseCase useCase = Mockito.mock(BulkPaymentUseCase.class);
        BulkPaymentsController controller = new BulkPaymentsController(useCase);

        Mockito.when(useCase.submitFile(Mockito.any(SubmitBulkFileCommand.class))).thenReturn(new BulkUploadResult(
                "FILE-001",
                BulkFileStatus.PROCESSING,
                "ix-1",
                false,
                1,
                0,
                Instant.parse("2026-02-09T10:00:00Z")
        ));
        Mockito.when(useCase.getFileStatus(Mockito.any())).thenReturn(Optional.of(file("FILE-001", BulkFileStatus.PROCESSING, BulkFileStatus.COMPLETED, 0, null)));
        Mockito.when(useCase.getFileReport(Mockito.any())).thenReturn(Optional.of(report("FILE-001", BulkFileStatus.COMPLETED)));

        ResponseEntity<BulkUploadResponse> upload = controller.uploadFile(
                "DPoP token",
                "proof",
                "ix-1",
                "TPP-001",
                "IDEMP-001",
                fileRequest("CONS-BULK-001", "payroll.csv", "content", "hash", "PARTIAL_REJECTION")
        );

        ResponseEntity<BulkFileStatusResponse> status = controller.getFileStatus(
                "DPoP token",
                "proof",
                "ix-1",
                "TPP-001",
                "FILE-001",
                null
        );

        ResponseEntity<BulkFileReportResponse> report = controller.getFileReport(
                "DPoP token",
                "proof",
                "ix-1",
                "TPP-001",
                "FILE-001",
                null
        );

        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(upload.getHeaders().getFirst("X-OF-Idempotency")).isEqualTo("MISS");
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(report.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void shouldReturnNotModifiedWhenEtagMatches() {
        BulkPaymentUseCase useCase = Mockito.mock(BulkPaymentUseCase.class);
        BulkPaymentsController controller = new BulkPaymentsController(useCase);

        Mockito.when(useCase.getFileStatus(Mockito.any())).thenReturn(Optional.of(file("FILE-001", BulkFileStatus.COMPLETED, BulkFileStatus.COMPLETED, 2, Instant.parse("2026-02-09T10:00:02Z"))));
        Mockito.when(useCase.getFileReport(Mockito.any())).thenReturn(Optional.of(report("FILE-001", BulkFileStatus.COMPLETED)));

        ResponseEntity<BulkFileStatusResponse> statusFirst = controller.getFileStatus("DPoP token", "proof", "ix-1", "TPP-001", "FILE-001", null);
        ResponseEntity<BulkFileReportResponse> reportFirst = controller.getFileReport("DPoP token", "proof", "ix-1", "TPP-001", "FILE-001", null);

        ResponseEntity<BulkFileStatusResponse> statusSecond = controller.getFileStatus("DPoP token", "proof", "ix-1", "TPP-001", "FILE-001", statusFirst.getHeaders().getETag());
        ResponseEntity<BulkFileReportResponse> reportSecond = controller.getFileReport("DPoP token", "proof", "ix-1", "TPP-001", "FILE-001", reportFirst.getHeaders().getETag());

        assertThat(statusSecond.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(reportSecond.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    }

    @Test
    void shouldReturnNotFoundWhenResourceMissing() {
        BulkPaymentUseCase useCase = Mockito.mock(BulkPaymentUseCase.class);
        BulkPaymentsController controller = new BulkPaymentsController(useCase);

        Mockito.when(useCase.getFileStatus(Mockito.any())).thenReturn(Optional.empty());
        Mockito.when(useCase.getFileReport(Mockito.any())).thenReturn(Optional.empty());

        ResponseEntity<BulkFileStatusResponse> status = controller.getFileStatus("DPoP token", "proof", "ix-1", "TPP-001", "FILE-404", null);
        ResponseEntity<BulkFileReportResponse> report = controller.getFileReport("DPoP token", "proof", "ix-1", "TPP-001", "FILE-404", null);

        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(report.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void shouldRejectUnsupportedAuthorizationType() {
        BulkPaymentUseCase useCase = Mockito.mock(BulkPaymentUseCase.class);
        BulkPaymentsController controller = new BulkPaymentsController(useCase);

        assertThatThrownBy(() -> controller.uploadFile(
                "Basic token",
                "proof",
                "ix-1",
                "TPP-001",
                "IDEMP-001",
                fileRequest("CONS-BULK-001", "payroll.csv", "content", "hash", "PARTIAL_REJECTION")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Bearer or DPoP");
    }

    private static BulkFile file(String fileId,
                                 BulkFileStatus status,
                                 BulkFileStatus targetStatus,
                                 int pollCount,
                                 Instant processedAt) {
        return new BulkFile(
                fileId,
                "CONS-BULK-001",
                "TPP-001",
                "IDEMP-001",
                "hash-1",
                "payroll.csv",
                BulkIntegrityMode.PARTIAL_REJECTION,
                status,
                targetStatus,
                pollCount,
                2,
                1,
                1,
                new BigDecimal("20.00"),
                Instant.parse("2026-02-09T10:00:00Z"),
                processedAt
        );
    }

    private static BulkFileReport report(String fileId, BulkFileStatus status) {
        return new BulkFileReport(
                fileId,
                status,
                2,
                1,
                1,
                List.of(
                        BulkItemResult.accepted(1, "INS-1", "AE120001000000000000000001", new BigDecimal("10.00")),
                        BulkItemResult.rejected(2, "INS-2", "AE000", new BigDecimal("10.00"), "Invalid IBAN")
                ),
                Instant.parse("2026-02-09T10:00:02Z")
        );
    }

    private static BulkFileRequest fileRequest(String consentId,
                                               String fileName,
                                               String fileContent,
                                               String fileHash,
                                               String integrityMode) {
        return new BulkFileRequest(new BulkFileRequest.Data(
                consentId,
                fileName,
                fileContent,
                fileHash,
                integrityMode
        ));
    }
}
