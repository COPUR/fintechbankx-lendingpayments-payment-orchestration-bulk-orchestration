package com.enterprise.openfinance.bulkpayments.domain.service;

import com.enterprise.openfinance.bulkpayments.domain.exception.BusinessRuleViolationException;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkFileStatus;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkIntegrityMode;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkItemResult;
import com.enterprise.openfinance.bulkpayments.domain.model.Money;
import com.enterprise.openfinance.bulkpayments.domain.model.ParsedBulkFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Currency;
import java.util.List;

/**
 * Domain service holding the file rules: payload size, integrity hash, CSV
 * schema ({@code instruction_id,payee_iban,amount}), the file currency and its
 * minor units (from the request; the CSV has no currency column), per-item validation and
 * the integrity mode. A structural error rejects the whole upload; an item
 * that fails validation is kept as a rejected item.
 */
public final class BulkFileParser {

    public static final String EXPECTED_HEADER = "instruction_id,payee_iban,amount";
    static final String FULL_REJECTION_REASON = "Rejected due to full rejection mode";

    private BulkFileParser() {
    }

    public static void verifyPayload(String fileContent, long maxFileSizeBytes) {
        if (fileContent == null || fileContent.isBlank()) {
            throw new BusinessRuleViolationException("Empty Payload");
        }
        long payloadSize = fileContent.getBytes(StandardCharsets.UTF_8).length;
        if (payloadSize > maxFileSizeBytes) {
            throw new BusinessRuleViolationException("Payload Too Large");
        }
    }

    /** The client sends the unpadded base64url SHA-256 of the file content. */
    public static void verifyHash(String fileContent, String expectedHash) {
        if (!sha256(fileContent).equals(expectedHash)) {
            throw new BusinessRuleViolationException("Integrity Failure");
        }
    }

    /** The file currency: an ISO 4217 code (upper case) whose minor units are defined. */
    public static Currency currency(String code) {
        if (code == null || code.isBlank()) {
            throw new BusinessRuleViolationException("Currency Required");
        }
        if (!code.matches("[A-Z]{3}")) {
            throw new BusinessRuleViolationException("Unsupported Currency");
        }
        Currency currency;
        try {
            currency = Currency.getInstance(code);
        } catch (IllegalArgumentException exception) {
            throw new BusinessRuleViolationException("Unsupported Currency");
        }
        if (currency.getDefaultFractionDigits() < 0) {
            throw new BusinessRuleViolationException("Unsupported Currency");
        }
        return currency;
    }

    public static ParsedBulkFile parse(String fileContent, BulkIntegrityMode mode, Currency currency) {
        String[] lines = fileContent.split("\\r?\\n");
        if (lines.length < 2) {
            throw new BusinessRuleViolationException("Empty Payload");
        }
        if (!EXPECTED_HEADER.equals(lines[0].trim().toLowerCase())) {
            throw new BusinessRuleViolationException("Schema Validation Failed");
        }

        List<BulkItemResult> items = new ArrayList<>(lines.length - 1);
        int accepted = 0;
        int rejected = 0;
        Money totalAmount = Money.zero(currency);
        Money acceptedAmount = Money.zero(currency);

        int logicalLine = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                continue;
            }
            logicalLine++;
            String[] columns = line.split(",", -1);
            if (columns.length != 3) {
                throw new BusinessRuleViolationException("Schema Validation Failed");
            }
            String instructionId = columns[0].trim();
            String payeeIban = columns[1].trim();
            Money amount = parseAmount(columns[2].trim(), currency);
            if (instructionId.isBlank() || payeeIban.isBlank()) {
                throw new BusinessRuleViolationException("Schema Validation Failed");
            }

            totalAmount = totalAmount.add(amount);
            if (isLikelyIban(payeeIban)) {
                items.add(BulkItemResult.accepted(logicalLine, instructionId, payeeIban, amount));
                accepted++;
                acceptedAmount = acceptedAmount.add(amount);
            } else {
                items.add(BulkItemResult.rejected(logicalLine, instructionId, payeeIban, amount, "Invalid IBAN"));
                rejected++;
            }
        }

        int totalCount = accepted + rejected;
        if (totalCount == 0) {
            throw new BusinessRuleViolationException("Empty Payload");
        }

        if (mode == BulkIntegrityMode.FULL_REJECTION && rejected > 0) {
            List<BulkItemResult> allRejected = items.stream()
                    .map(item -> BulkItemResult.rejected(item.lineNumber(), item.instructionId(), item.payeeIban(),
                            item.amount(), item.errorMessage() == null ? FULL_REJECTION_REASON : item.errorMessage()))
                    .toList();
            return new ParsedBulkFile(allRejected, totalCount, 0, totalCount, totalAmount, Money.zero(currency),
                    BulkFileStatus.REJECTED);
        }

        BulkFileStatus targetStatus = accepted == 0 ? BulkFileStatus.REJECTED : BulkFileStatus.VALIDATED;
        return new ParsedBulkFile(items, totalCount, accepted, rejected, totalAmount, acceptedAmount, targetStatus);
    }

    private static Money parseAmount(String raw, Currency currency) {
        if (raw.isBlank()) {
            throw new BusinessRuleViolationException("Schema Validation Failed");
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(raw);
        } catch (NumberFormatException exception) {
            throw new BusinessRuleViolationException("Schema Validation Failed");
        }
        if (amount.signum() <= 0) {
            throw new BusinessRuleViolationException("Schema Validation Failed");
        }
        if (amount.stripTrailingZeros().scale() > currency.getDefaultFractionDigits()) {
            throw new BusinessRuleViolationException("Amount Precision Exceeds Currency Minor Units");
        }
        return new Money(amount, currency);
    }

    static boolean isLikelyIban(String value) {
        String normalized = value.trim().toUpperCase();
        if (normalized.length() < 15 || normalized.length() > 34) {
            return false;
        }
        if (!Character.isLetter(normalized.charAt(0)) || !Character.isLetter(normalized.charAt(1))) {
            return false;
        }
        if (!Character.isDigit(normalized.charAt(2)) || !Character.isDigit(normalized.charAt(3))) {
            return false;
        }
        for (int i = 0; i < normalized.length(); i++) {
            if (!Character.isLetterOrDigit(normalized.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to hash payload", exception);
        }
    }
}
