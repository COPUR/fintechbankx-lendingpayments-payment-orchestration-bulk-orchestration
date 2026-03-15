package com.enterprise.openfinance.bulkpayments.domain.command;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkIntegrityMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubmitBulkFileCommandTest {

    @Test
    void shouldCreateAndNormalizeCommand() {
        SubmitBulkFileCommand command = new SubmitBulkFileCommand(
                " TPP-001 ",
                " CONS-BULK-001 ",
                " IDEMP-001 ",
                " payroll.csv ",
                " instruction_id,payee_iban,amount\\nINS-1,AE120001000000000000000001,10.00 ",
                " hash-123 ",
                BulkIntegrityMode.PARTIAL_REJECTION,
                " ix-1 "
        );

        assertThat(command.tppId()).isEqualTo("TPP-001");
        assertThat(command.consentId()).isEqualTo("CONS-BULK-001");
        assertThat(command.idempotencyKey()).isEqualTo("IDEMP-001");
        assertThat(command.fileName()).isEqualTo("payroll.csv");
        assertThat(command.fileHash()).isEqualTo("hash-123");
        assertThat(command.interactionId()).isEqualTo("ix-1");
        assertThat(command.requestHash()).contains("CONS-BULK-001|payroll.csv|");
    }

    @Test
    void shouldRejectInvalidCommand() {
        assertInvalid("", "CONS-BULK-001", "IDEMP-001", "payroll.csv", "x", "hash", BulkIntegrityMode.PARTIAL_REJECTION, "ix-1", "tppId");
        assertInvalid("TPP-001", "", "IDEMP-001", "payroll.csv", "x", "hash", BulkIntegrityMode.PARTIAL_REJECTION, "ix-1", "consentId");
        assertInvalid("TPP-001", "CONS-BULK-001", "", "payroll.csv", "x", "hash", BulkIntegrityMode.PARTIAL_REJECTION, "ix-1", "idempotencyKey");
        assertInvalid("TPP-001", "CONS-BULK-001", "IDEMP-001", "", "x", "hash", BulkIntegrityMode.PARTIAL_REJECTION, "ix-1", "fileName");
        assertInvalid("TPP-001", "CONS-BULK-001", "IDEMP-001", "payroll.csv", null, "hash", BulkIntegrityMode.PARTIAL_REJECTION, "ix-1", "fileContent");
        assertInvalid("TPP-001", "CONS-BULK-001", "IDEMP-001", "payroll.csv", "x", "", BulkIntegrityMode.PARTIAL_REJECTION, "ix-1", "fileHash");
        assertInvalid("TPP-001", "CONS-BULK-001", "IDEMP-001", "payroll.csv", "x", "hash", null, "ix-1", "integrityMode");
        assertInvalid("TPP-001", "CONS-BULK-001", "IDEMP-001", "payroll.csv", "x", "hash", BulkIntegrityMode.PARTIAL_REJECTION, "", "interactionId");
    }

    private static void assertInvalid(String tppId,
                                      String consentId,
                                      String idempotencyKey,
                                      String fileName,
                                      String fileContent,
                                      String fileHash,
                                      BulkIntegrityMode mode,
                                      String interactionId,
                                      String expectedField) {
        assertThatThrownBy(() -> new SubmitBulkFileCommand(
                tppId,
                consentId,
                idempotencyKey,
                fileName,
                fileContent,
                fileHash,
                mode,
                interactionId
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedField);
    }
}
