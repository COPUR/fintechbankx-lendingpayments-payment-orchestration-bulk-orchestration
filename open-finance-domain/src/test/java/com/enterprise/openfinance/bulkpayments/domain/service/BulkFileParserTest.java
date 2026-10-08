package com.enterprise.openfinance.bulkpayments.domain.service;

import com.enterprise.openfinance.bulkpayments.domain.exception.BusinessRuleViolationException;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkFileStatus;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkIntegrityMode;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkItemStatus;
import com.enterprise.openfinance.bulkpayments.domain.model.ParsedBulkFile;
import com.enterprise.openfinance.bulkpayments.domain.model.Money;
import org.junit.jupiter.api.Test;

import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BulkFileParserTest {

    private static final String GOOD_IBAN = "AE120001000000000000000001";
    private static final Currency AED = Currency.getInstance("AED");
    private static final Currency KWD = Currency.getInstance("KWD");
    private static final Currency JPY = Currency.getInstance("JPY");

    @Test
    void everyAmountCarriesTheFileCurrency() {
        ParsedBulkFile parsed = BulkFileParser.parse(csv("INS-1," + GOOD_IBAN + ",1500"), BulkIntegrityMode.PARTIAL_REJECTION, JPY);

        assertThat(parsed.items().get(0).amount()).isEqualTo(Money.of("1500", "JPY"));
        assertThat(parsed.totalAmount()).isEqualTo(Money.of("1500", "JPY"));
        assertThat(parsed.acceptedAmount().currency()).isEqualTo(JPY);
    }

    @Test
    void rejectsAmountsFinerThanTheCurrencyMinorUnit() {
        assertThatThrownBy(() -> BulkFileParser.parse(csv("INS-1," + GOOD_IBAN + ",10.001"),
                BulkIntegrityMode.PARTIAL_REJECTION, AED))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Amount Precision Exceeds Currency Minor Units");
        assertThatThrownBy(() -> BulkFileParser.parse(csv("INS-1," + GOOD_IBAN + ",10.5"),
                BulkIntegrityMode.PARTIAL_REJECTION, JPY))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Amount Precision Exceeds Currency Minor Units");
    }

    @Test
    void currencyMustBeAnIso4217CodeWithMinorUnits() {
        assertThat(BulkFileParser.currency("KWD")).isEqualTo(KWD);
        for (String bad : new String[] {null, " ", "aed", "AEDX", "ZZZ", "XAU"}) {
            assertThatThrownBy(() -> BulkFileParser.currency(bad))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageStartingWith(bad == null || bad.isBlank() ? "Currency Required" : "Unsupported Currency");
        }
    }

    @Test
    void allValidItemsCompleteWithExactTotals() {
        ParsedBulkFile parsed = BulkFileParser.parse(csv(
                "INS-1," + GOOD_IBAN + ",10.00",
                "",
                "INS-2," + GOOD_IBAN + ",0.10",
                "INS-3," + GOOD_IBAN + ",2500.255"), BulkIntegrityMode.PARTIAL_REJECTION, KWD);

        assertThat(parsed.totalCount()).isEqualTo(3);
        assertThat(parsed.acceptedCount()).isEqualTo(3);
        assertThat(parsed.rejectedCount()).isZero();
        assertThat(parsed.totalAmount().amount()).isEqualByComparingTo("2510.355");
        assertThat(parsed.acceptedAmount().amount()).isEqualByComparingTo("2510.355");
        assertThat(parsed.targetStatus()).isEqualTo(BulkFileStatus.VALIDATED);
        assertThat(parsed.items()).extracting("lineNumber").containsExactly(1, 2, 3);
    }

    @Test
    void partialRejectionKeepsValidItemsAndCountsOnlyTheirAmount() {
        ParsedBulkFile parsed = BulkFileParser.parse(csv(
                "INS-1," + GOOD_IBAN + ",10.00",
                "INS-2,AE000,20.00"), BulkIntegrityMode.PARTIAL_REJECTION, AED);

        assertThat(parsed.targetStatus()).isEqualTo(BulkFileStatus.VALIDATED);
        assertThat(parsed.acceptedCount()).isEqualTo(1);
        assertThat(parsed.rejectedCount()).isEqualTo(1);
        assertThat(parsed.totalAmount().amount()).isEqualByComparingTo("30.00");
        assertThat(parsed.acceptedAmount().amount()).isEqualByComparingTo("10.00");
        assertThat(parsed.items().get(1).status()).isEqualTo(BulkItemStatus.REJECTED);
        assertThat(parsed.items().get(1).errorMessage()).isEqualTo("Invalid IBAN");
    }

    @Test
    void fullRejectionRejectsEveryItemWhenOneIsInvalid() {
        ParsedBulkFile parsed = BulkFileParser.parse(csv(
                "INS-1," + GOOD_IBAN + ",10.00",
                "INS-2,AE000,20.00"), BulkIntegrityMode.FULL_REJECTION, AED);

        assertThat(parsed.targetStatus()).isEqualTo(BulkFileStatus.REJECTED);
        assertThat(parsed.acceptedCount()).isZero();
        assertThat(parsed.rejectedCount()).isEqualTo(2);
        assertThat(parsed.acceptedAmount().amount()).isEqualByComparingTo("0");
        assertThat(parsed.items()).extracting("errorMessage")
                .containsExactly("Rejected due to full rejection mode", "Invalid IBAN");
    }

    @Test
    void fileWhereEveryIbanIsInvalidIsRejected() {
        ParsedBulkFile parsed = BulkFileParser.parse(csv("INS-1,XX,10.00"), BulkIntegrityMode.PARTIAL_REJECTION, AED);

        assertThat(parsed.targetStatus()).isEqualTo(BulkFileStatus.REJECTED);
    }

    @Test
    void structuralErrorsRejectTheWholeUpload() {
        assertSchemaFailure("bad_header\nINS-1," + GOOD_IBAN + ",10.00");
        assertSchemaFailure(csv("INS-1," + GOOD_IBAN));
        assertSchemaFailure(csv("," + GOOD_IBAN + ",10.00"));
        assertSchemaFailure(csv("INS-1,,10.00"));
        assertSchemaFailure(csv("INS-1," + GOOD_IBAN + ","));
        assertSchemaFailure(csv("INS-1," + GOOD_IBAN + ",ten"));
        assertSchemaFailure(csv("INS-1," + GOOD_IBAN + ",0.00"));
        assertSchemaFailure(csv("INS-1," + GOOD_IBAN + ",-5"));

        assertThatThrownBy(() -> BulkFileParser.parse(csv(), BulkIntegrityMode.PARTIAL_REJECTION, AED))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessage("Empty Payload");
        assertThatThrownBy(() -> BulkFileParser.parse(csv("", " "), BulkIntegrityMode.PARTIAL_REJECTION, AED))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessage("Empty Payload");
    }

    @Test
    void payloadAndHashChecks() {
        assertThatThrownBy(() -> BulkFileParser.verifyPayload(" ", 100))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessage("Empty Payload");
        assertThatThrownBy(() -> BulkFileParser.verifyPayload("12345678901", 10))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessage("Payload Too Large");
        BulkFileParser.verifyPayload("1234567890", 10);

        String content = csv("INS-1," + GOOD_IBAN + ",10.00");
        BulkFileParser.verifyHash(content, "01fWq3K7Vfc7kU3kIWw7y-77cUZ1vcD9lWVs5ICT-tA");
        assertThatThrownBy(() -> BulkFileParser.verifyHash(content, "wrong"))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessage("Integrity Failure");
    }

    @Test
    void ibanFormatCheck() {
        assertThat(BulkFileParser.isLikelyIban(GOOD_IBAN)).isTrue();
        assertThat(BulkFileParser.isLikelyIban("ae120001000000000000000001")).isTrue();
        assertThat(BulkFileParser.isLikelyIban("AE12000")).isFalse();
        assertThat(BulkFileParser.isLikelyIban("1E120001000000000000000001")).isFalse();
        assertThat(BulkFileParser.isLikelyIban("AEX20001000000000000000001")).isFalse();
        assertThat(BulkFileParser.isLikelyIban("AE12-001000000000000000001")).isFalse();
        assertThat(BulkFileParser.isLikelyIban("AE12" + "0".repeat(31))).isFalse();
    }

    private static void assertSchemaFailure(String content) {
        assertThatThrownBy(() -> BulkFileParser.parse(content, BulkIntegrityMode.PARTIAL_REJECTION, AED))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Schema Validation Failed");
    }

    private static String csv(String... rows) {
        StringBuilder builder = new StringBuilder(BulkFileParser.EXPECTED_HEADER);
        for (String row : rows) {
            builder.append('\n').append(row);
        }
        return builder.toString();
    }
}
