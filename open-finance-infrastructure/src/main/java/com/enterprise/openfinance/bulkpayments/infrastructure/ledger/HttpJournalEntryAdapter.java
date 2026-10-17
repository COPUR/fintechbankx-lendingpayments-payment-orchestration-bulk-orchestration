package com.enterprise.openfinance.bulkpayments.infrastructure.ledger;

import com.enterprise.openfinance.bulkpayments.domain.exception.AccountsLedgerUnavailableException;
import com.enterprise.openfinance.bulkpayments.domain.exception.JournalEntryRejectedException;
import com.enterprise.openfinance.bulkpayments.domain.model.JournalEntry;
import com.enterprise.openfinance.bulkpayments.domain.model.JournalEntryReceipt;
import com.enterprise.openfinance.bulkpayments.domain.model.JournalLine;
import com.enterprise.openfinance.bulkpayments.domain.port.out.JournalEntryPort;
import com.enterprise.openfinance.bulkpayments.infrastructure.rest.InteractionIdFilter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Anti-corruption adapter to the accounts ledger (svc-acc-accounts-ledger),
 * POST /api/v1/journal-entries. Calls carry this service's own client-credentials
 * token. The entry reference is the ledger's idempotency key: 201 books, 200 replays.
 * 409 and 422 with a code become {@link JournalEntryRejectedException}; anything
 * else fails closed with {@link AccountsLedgerUnavailableException} carrying the
 * status only, never the ledger's body.
 */
public class HttpJournalEntryAdapter implements JournalEntryPort {

    static final String JOURNAL_ENTRIES_PATH = "/api/v1/journal-entries";

    private final RestClient client;
    private static final Pattern LEDGER_CODE = Pattern.compile("[A-Z_]+");
    private final Supplier<String> serviceToken;

    public HttpJournalEntryAdapter(RestClient client, Supplier<String> serviceToken) {
        this.client = client;
        this.serviceToken = serviceToken;
    }

    @Override
    public JournalEntryReceipt post(JournalEntry entry) {
        ResponseEntity<JournalEntryResponse> response;
        try {
            response = client.post()
                    .uri(JOURNAL_ENTRIES_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + serviceToken.get())
                    .header("X-FAPI-Interaction-ID", interactionId())
                    .body(JournalEntryRequest.from(entry))
                    .retrieve()
                    .toEntity(JournalEntryResponse.class);
        } catch (HttpClientErrorException exception) {
            HttpStatusCode status = exception.getStatusCode();
            if (status.isSameCodeAs(HttpStatus.CONFLICT) || status.isSameCodeAs(HttpStatus.UNPROCESSABLE_ENTITY)) {
                String code = rejectionCode(exception);
                if (code != null) {
                    throw new JournalEntryRejectedException(entry.reference(), code);
                }
            }
            throw new AccountsLedgerUnavailableException("Accounts ledger refused the posting: " + status.value(), null);
        } catch (RestClientResponseException exception) {
            throw new AccountsLedgerUnavailableException("Accounts ledger refused the posting: "
                    + exception.getStatusCode().value(), null);
        } catch (RestClientException | IllegalStateException exception) {
            throw new AccountsLedgerUnavailableException("Accounts ledger unavailable", exception);
        }
        JournalEntryResponse body = response.getBody();
        if (body == null || body.journalEntryId() == null || body.reference() == null
                || body.bookedAt() == null || body.replay() == null) {
            throw new AccountsLedgerUnavailableException("Accounts ledger returned an incomplete booking", null);
        }
        if (!entry.reference().equals(body.reference())) {
            throw new AccountsLedgerUnavailableException("Accounts ledger answered for another reference", null);
        }
        return new JournalEntryReceipt(body.journalEntryId(), entry.reference(), body.bookedAt(), body.replay());
    }

    /** The ledger's error body is {code, message, interactionId, timestamp}; only the code is read. */
    /** Only a plain ledger code (A-Z and underscore) is passed on; anything else is treated as no code. */
    private static String rejectionCode(HttpClientErrorException exception) {
        try {
            LedgerError error = exception.getResponseBodyAs(LedgerError.class);
            return error == null || error.code() == null || !LEDGER_CODE.matcher(error.code()).matches()
                    ? null : error.code();
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static String interactionId() {
        String fromRequest = MDC.get(InteractionIdFilter.MDC_KEY);
        return fromRequest != null ? fromRequest : UUID.randomUUID().toString();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record JournalEntryRequest(String reference, String currency, String narrative, List<Line> lines) {

        static JournalEntryRequest from(JournalEntry entry) {
            return new JournalEntryRequest(entry.reference(), entry.currency().getCurrencyCode(), entry.narrative(),
                    entry.lines().stream().map(Line::from).toList());
        }

        record Line(String accountId, String side, BigDecimal amount) {

            static Line from(JournalLine line) {
                return new Line(line.accountId(), line.side().name(), line.amount().amount());
            }
        }
    }

    /** The fields this service reads from the ledger's booking; the lines and any new field are ignored. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record JournalEntryResponse(String journalEntryId, String reference, Instant bookedAt, Boolean replay) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LedgerError(String code) {
    }
}
