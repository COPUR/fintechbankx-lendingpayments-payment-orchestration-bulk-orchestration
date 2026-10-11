package com.enterprise.openfinance.bulkpayments.domain.model;

import com.enterprise.openfinance.bulkpayments.domain.service.BulkFileParser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BulkConsentBindingTest {

    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");
    private static final String IBAN = "AE120001000000000000000001";

    @Test
    void bindsTheConsentToTheFileWithItsHashItemCountAndControlSum() {
        String content = "instruction_id,payee_iban,amount\nINS-1," + IBAN + ",1.234\nINS-2,AE000,0.5\nINS-3,"
                + IBAN + ",2";
        BulkFile file = BulkFile.accept("FILE-1", "CONS-1", "TPP-001", "IDEMP-1", "hash", "payroll.csv",
                BulkIntegrityMode.PARTIAL_REJECTION,
                BulkFileParser.parse(content, BulkIntegrityMode.PARTIAL_REJECTION, Currency.getInstance("KWD")), NOW.plusSeconds(3600), NOW);

        BulkConsentBinding binding = BulkConsentBinding.of(file, "sha-of-file", NOW);

        assertThat(binding.consentId()).isEqualTo("CONS-1");
        assertThat(binding.tppId()).isEqualTo("TPP-001");
        assertThat(binding.fileId()).isEqualTo("FILE-1");
        assertThat(binding.fileHash()).isEqualTo("sha-of-file");
        // Every line of the file counts, rejected ones included: the consent covered the whole file.
        assertThat(binding.itemCount()).isEqualTo(3);
        assertThat(binding.controlSum()).isEqualTo(Money.of("3.734", "KWD"));
        assertThat(binding.boundAt()).isEqualTo(NOW);
    }

    @Test
    void refusesIncompleteBindings() {
        Money sum = Money.of("10.00", "AED");
        assertThatThrownBy(() -> new BulkConsentBinding(" ", "TPP", "FILE", "h", 1, sum, NOW))
                .hasMessage("consentId is required");
        assertThatThrownBy(() -> new BulkConsentBinding("C", "TPP", null, "h", 1, sum, NOW))
                .hasMessage("fileId is required");
        assertThatThrownBy(() -> new BulkConsentBinding("C", "TPP", "FILE", "", 1, sum, NOW))
                .hasMessage("fileHash is required");
        assertThatThrownBy(() -> new BulkConsentBinding("C", "TPP", "FILE", "h", 0, sum, NOW))
                .hasMessage("itemCount must be positive");
        assertThatThrownBy(() -> new BulkConsentBinding("C", "TPP", "FILE", "h", 1, null, NOW))
                .hasMessage("controlSum is required");
    }
}
