package com.enterprise.openfinance.bulkpayments.infrastructure.consent;

import com.enterprise.openfinance.bulkpayments.domain.exception.ForbiddenException;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkConsentContext;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpBulkConsentAdapterTest {

    private static final String BASE = "http://consent-auth-service.open-finance.svc.cluster.local:8080";

    private final RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HttpBulkConsentAdapter adapter = new HttpBulkConsentAdapter(builder.build(), () -> "svc-token");

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void mapsAnAuthorisedConsentAndSendsTheServiceTokenNotTheCallers() {
        MDC.put(InteractionIdFilter.MDC_KEY, "ix-7");
        server.expect(requestTo(BASE + "/open-finance/v1/consents/CONS-1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer svc-token"))
                .andExpect(header("X-FAPI-Interaction-ID", "ix-7"))
                .andRespond(withSuccess("""
                        {"consentId":"CONS-1","customerId":"CUST-1","participantId":"TPP-001",
                         "scopes":["bulk-payment"],"purpose":"payroll","status":"AUTHORIZED",
                         "expiresAt":"2099-01-01T00:00:00Z","active":true}
                        """, MediaType.APPLICATION_JSON));

        BulkConsentContext consent = adapter.findById("CONS-1").orElseThrow();

        assertThat(consent.tppId()).isEqualTo("TPP-001");
        assertThat(consent.hasScope("bulk-payment")).isTrue();
        assertThat(consent.isAuthorized()).isTrue();
        assertThat(consent.expiresAt()).isEqualTo(Instant.parse("2099-01-01T00:00:00Z"));
        server.verify();
    }

    @Test
    void revokedOrInactiveConsentIsNotAuthorised() {
        server.expect(requestTo(BASE + "/open-finance/v1/consents/CONS-2"))
                .andRespond(withSuccess("""
                        {"consentId":"CONS-2","participantId":"TPP-001","scopes":["bulk-payment"],
                         "status":"REVOKED","expiresAt":"2099-01-01T00:00:00Z","active":false}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/open-finance/v1/consents/CONS-3"))
                .andRespond(withSuccess("""
                        {"consentId":"CONS-3","participantId":"TPP-001","scopes":["bulk-payment"],
                         "status":"AUTHORIZED","expiresAt":"2099-01-01T00:00:00Z","active":false}
                        """, MediaType.APPLICATION_JSON));

        assertThat(adapter.findById("CONS-2").orElseThrow().isAuthorized()).isFalse();
        assertThat(adapter.findById("CONS-3").orElseThrow().isAuthorized()).isFalse();
    }

    @Test
    void notFoundMeansNoConsent() {
        server.expect(requestTo(BASE + "/open-finance/v1/consents/CONS-404")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(adapter.findById("CONS-404")).isEmpty();
    }

    @Test
    void otherFailuresFailClosed() {
        server.expect(requestTo(BASE + "/open-finance/v1/consents/CONS-1")).andRespond(withServerError());
        assertThatThrownBy(() -> adapter.findById("CONS-1")).isInstanceOf(ConsentServiceUnavailableException.class);

        server.reset();
        server.expect(requestTo(BASE + "/open-finance/v1/consents/CONS-1")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> adapter.findById("CONS-1"))
                .isInstanceOf(ConsentServiceUnavailableException.class)
                .hasMessageContaining("401");

        server.reset();
        server.expect(requestTo(BASE + "/open-finance/v1/consents/CONS-1"))
                .andRespond(withSuccess("{\"consentId\":\"CONS-1\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> adapter.findById("CONS-1"))
                .isInstanceOf(ConsentServiceUnavailableException.class)
                .hasMessageContaining("incomplete");
    }

    @Test
    void missingServiceTokenFailsClosedWithoutCallingTheConsentService() {
        HttpBulkConsentAdapter noToken = new HttpBulkConsentAdapter(builder.build(), () -> {
            throw new IllegalStateException("No service token");
        });

        assertThatThrownBy(() -> noToken.findById("CONS-1")).isInstanceOf(ConsentServiceUnavailableException.class);
        server.verify();
    }

    @Test
    void consentWithoutScopesIsRefused() {
        server.expect(requestTo(BASE + "/open-finance/v1/consents/CONS-1"))
                .andRespond(withSuccess("""
                        {"consentId":"CONS-1","participantId":"TPP-001","scopes":[],
                         "status":"AUTHORIZED","expiresAt":"2099-01-01T00:00:00Z"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.findById("CONS-1"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("bulk-payment");
    }
}
