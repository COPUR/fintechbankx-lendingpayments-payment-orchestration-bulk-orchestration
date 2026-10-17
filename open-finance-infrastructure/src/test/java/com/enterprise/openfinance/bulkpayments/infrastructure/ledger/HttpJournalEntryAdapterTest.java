package com.enterprise.openfinance.bulkpayments.infrastructure.ledger;

import com.enterprise.openfinance.bulkpayments.domain.exception.AccountsLedgerUnavailableException;
import com.enterprise.openfinance.bulkpayments.domain.exception.JournalEntryRejectedException;
import com.enterprise.openfinance.bulkpayments.domain.model.JournalEntry;
import com.enterprise.openfinance.bulkpayments.domain.model.JournalEntryReceipt;
import com.enterprise.openfinance.bulkpayments.domain.model.JournalLine;
import com.enterprise.openfinance.bulkpayments.domain.model.JournalSide;
import com.enterprise.openfinance.bulkpayments.domain.model.Money;
import com.enterprise.openfinance.bulkpayments.infrastructure.rest.InteractionIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withCreatedEntity;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpJournalEntryAdapterTest {

    private static final String BASE = "http://accounts-ledger-service.corebanking.svc.cluster.local:8080";
    private static final String PATH = BASE + "/api/v1/journal-entries";
    private static final String BOOKED = """
            {"journalEntryId":"JE-42","reference":"BULK-1/ITEM-7","currency":"AED",
             "lines":[{"accountId":"ACC-DEBTOR","side":"DEBIT","amount":40.00},
                      {"accountId":"ACC-CLEARING","side":"CREDIT","amount":40.00}],
             "narrative":"bulk item 7","bookedAt":"2026-10-17T10:00:00Z","replay":%s,"futureField":"ignored"}
            """;

    private final RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HttpJournalEntryAdapter adapter = new HttpJournalEntryAdapter(builder.build(), () -> "svc-token");

    private final JournalEntry entry = new JournalEntry("BULK-1/ITEM-7", Currency.getInstance("AED"), "bulk item 7",
            List.of(new JournalLine("ACC-DEBTOR", JournalSide.DEBIT, Money.of("40.00", "AED")),
                    new JournalLine("ACC-CLEARING", JournalSide.CREDIT, Money.of("40.00", "AED"))));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void postsTheContractBodyWithTheServiceTokenAndMapsABooking() {
        MDC.put(InteractionIdFilter.MDC_KEY, "ix-7");
        server.expect(requestTo(PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer svc-token"))
                .andExpect(header("X-FAPI-Interaction-ID", "ix-7"))
                .andExpect(headerDoesNotExist("x-idempotency-key"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.reference").value("BULK-1/ITEM-7"))
                .andExpect(jsonPath("$.currency").value("AED"))
                .andExpect(jsonPath("$.narrative").value("bulk item 7"))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].accountId").value("ACC-DEBTOR"))
                .andExpect(jsonPath("$.lines[0].side").value("DEBIT"))
                .andExpect(jsonPath("$.lines[0].amount").value(40.00))
                .andExpect(jsonPath("$.lines[1].accountId").value("ACC-CLEARING"))
                .andExpect(jsonPath("$.lines[1].side").value("CREDIT"))
                .andExpect(jsonPath("$.lines[1].amount").value(40.00))
                .andExpect(jsonPath("$.length()").value(4))
                .andRespond(withCreatedEntity(null).body(BOOKED.formatted("false"))
                        .contentType(MediaType.APPLICATION_JSON));

        JournalEntryReceipt receipt = adapter.post(entry);

        assertThat(receipt.journalEntryId()).isEqualTo("JE-42");
        assertThat(receipt.reference()).isEqualTo("BULK-1/ITEM-7");
        assertThat(receipt.bookedAt()).isEqualTo(Instant.parse("2026-10-17T10:00:00Z"));
        assertThat(receipt.replay()).isFalse();
        server.verify();
    }

    @Test
    void omitsAnAbsentNarrativeAndAlwaysSendsAnInteractionId() {
        JournalEntry noNarrative = new JournalEntry("BULK-1/ITEM-8", Currency.getInstance("AED"), null, entry.lines());
        server.expect(requestTo(PATH))
                .andExpect(header("X-FAPI-Interaction-ID", org.hamcrest.Matchers.not(org.hamcrest.Matchers.blankOrNullString())))
                .andExpect(jsonPath("$.narrative").doesNotExist())
                .andExpect(jsonPath("$.length()").value(3))
                .andRespond(withCreatedEntity(null)
                        .body(BOOKED.formatted("false").replace("BULK-1/ITEM-7", "BULK-1/ITEM-8"))
                        .contentType(MediaType.APPLICATION_JSON));

        assertThat(adapter.post(noNarrative).reference()).isEqualTo("BULK-1/ITEM-8");
        server.verify();
    }

    @Test
    void aReplayAnswersTheFirstBooking() {
        server.expect(requestTo(PATH))
                .andRespond(withSuccess(BOOKED.formatted("true"), MediaType.APPLICATION_JSON));

        JournalEntryReceipt receipt = adapter.post(entry);

        assertThat(receipt.journalEntryId()).isEqualTo("JE-42");
        assertThat(receipt.replay()).isTrue();
    }

    @Test
    void conflictAndUnprocessableCarryTheLedgerCode() {
        server.expect(requestTo(PATH)).andRespond(withStatus(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"IDEMPOTENCY_KEY_REUSED\",\"message\":\"reference reused\",\"timestamp\":\"2026-10-17T10:00:00Z\"}"));
        assertThatThrownBy(() -> adapter.post(entry))
                .isInstanceOfSatisfying(JournalEntryRejectedException.class, rejected -> {
                    assertThat(rejected.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
                    assertThat(rejected.reference()).isEqualTo("BULK-1/ITEM-7");
                    assertThat(rejected.isRetryable()).isFalse();
                });

        server.reset();
        server.expect(requestTo(PATH)).andRespond(withStatus(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"CONCURRENT_UPDATE\",\"message\":\"retry\",\"timestamp\":\"2026-10-17T10:00:00Z\"}"));
        assertThatThrownBy(() -> adapter.post(entry))
                .isInstanceOfSatisfying(JournalEntryRejectedException.class,
                        rejected -> assertThat(rejected.isRetryable()).isTrue());

        server.reset();
        server.expect(requestTo(PATH)).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"ACCOUNT_NOT_OPERATIONAL\",\"message\":\"frozen\",\"timestamp\":\"2026-10-17T10:00:00Z\"}"));
        assertThatThrownBy(() -> adapter.post(entry))
                .isInstanceOfSatisfying(JournalEntryRejectedException.class,
                        rejected -> assertThat(rejected.code()).isEqualTo("ACCOUNT_NOT_OPERATIONAL"));
    }

    @Test
    void aRejectionNeverCarriesTheLedgerMessageOrBody() {
        server.expect(requestTo(PATH)).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"INSUFFICIENT_FUNDS\",\"message\":\"ACC-DEBTOR available balance 12.50 below 40.00\","
                        + "\"timestamp\":\"2026-10-17T10:00:00Z\"}"));

        assertThatThrownBy(() -> adapter.post(entry))
                .isInstanceOfSatisfying(JournalEntryRejectedException.class, rejected -> {
                    assertThat(rejected.code()).isEqualTo("INSUFFICIENT_FUNDS");
                    assertThat(rejected.getMessage()).doesNotContain("12.50", "ACC-DEBTOR", "balance");
                    assertThat(rejected.getCause()).isNull();
                });
    }

    @Test
    void aRejectionWithoutACodeFailsClosed() {
        server.expect(requestTo(PATH)).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"message\":\"no code\"}"));
        assertThatThrownBy(() -> adapter.post(entry))
                .isInstanceOf(AccountsLedgerUnavailableException.class)
                .hasMessageContaining("422")
                .hasNoCause();

        server.reset();
        server.expect(requestTo(PATH)).andRespond(withStatus(HttpStatus.CONFLICT)
                .contentType(MediaType.TEXT_PLAIN).body("not json"));
        assertThatThrownBy(() -> adapter.post(entry))
                .isInstanceOf(AccountsLedgerUnavailableException.class)
                .hasMessageContaining("409")
                .hasNoCause();
    }

    @Test
    void everyOtherStatusFailsClosedWithTheStatusOnly() {
        server.expect(requestTo(PATH)).andRespond(withServerError()
                .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"INTERNAL\",\"message\":\"ACC-1 balance 7.00\"}"));
        assertThatThrownBy(() -> adapter.post(entry))
                .isInstanceOf(AccountsLedgerUnavailableException.class)
                .hasMessageContaining("500")
                .hasNoCause()
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("ACC-1", "7.00"));

        server.reset();
        server.expect(requestTo(PATH)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> adapter.post(entry))
                .isInstanceOf(AccountsLedgerUnavailableException.class)
                .hasMessageContaining("401");

        server.reset();
        server.expect(requestTo(PATH)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> adapter.post(entry))
                .isInstanceOf(AccountsLedgerUnavailableException.class)
                .hasMessageContaining("403");

        server.reset();
        server.expect(requestTo(PATH)).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> adapter.post(entry))
                .isInstanceOf(AccountsLedgerUnavailableException.class)
                .hasMessageContaining("400");

        server.reset();
        server.expect(requestTo(PATH)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> adapter.post(entry))
                .isInstanceOf(AccountsLedgerUnavailableException.class)
                .hasMessageContaining("404");
    }

    @Test
    void anIncompleteOrForeignBookingFailsClosed() {
        server.expect(requestTo(PATH))
                .andRespond(withSuccess("{\"journalEntryId\":\"JE-42\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> adapter.post(entry))
                .isInstanceOf(AccountsLedgerUnavailableException.class)
                .hasMessageContaining("incomplete");

        server.reset();
        server.expect(requestTo(PATH)).andRespond(withSuccess(
                BOOKED.formatted("false").replace("BULK-1/ITEM-7", "OTHER-REF"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> adapter.post(entry))
                .isInstanceOf(AccountsLedgerUnavailableException.class)
                .hasMessageContaining("another reference");

        server.reset();
        server.expect(requestTo(PATH)).andRespond(withStatus(HttpStatus.NO_CONTENT));
        assertThatThrownBy(() -> adapter.post(entry))
                .isInstanceOf(AccountsLedgerUnavailableException.class)
                .hasMessageContaining("incomplete");
    }

    @Test
    void transportFailuresFailClosed() {
        server.expect(requestTo(PATH)).andRespond(request -> {
            throw new java.io.IOException("connection reset");
        });

        assertThatThrownBy(() -> adapter.post(entry))
                .isInstanceOf(AccountsLedgerUnavailableException.class)
                .hasMessage("Accounts ledger unavailable")
                .hasCauseInstanceOf(org.springframework.web.client.ResourceAccessException.class);
    }

    @Test
    void missingServiceTokenFailsClosedWithoutCallingTheLedger() {
        HttpJournalEntryAdapter noToken = new HttpJournalEntryAdapter(builder.build(), () -> {
            throw new IllegalStateException("No service token");
        });

        assertThatThrownBy(() -> noToken.post(entry)).isInstanceOf(AccountsLedgerUnavailableException.class);
        server.verify();
    }

    @Test
    void postsToTheJournalEntriesPathOnly() {
        assertThat(HttpJournalEntryAdapter.JOURNAL_ENTRIES_PATH).isEqualTo("/api/v1/journal-entries");
    }
}
