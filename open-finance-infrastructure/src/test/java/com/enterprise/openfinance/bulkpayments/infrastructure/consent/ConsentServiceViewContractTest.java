package com.enterprise.openfinance.bulkpayments.infrastructure.consent;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkConsentContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Consumer contract with consent-authorization-service (fintechbankx-openfinance-consent-auth-service,
 * branch claude/openfinance-deployable-ra36dq at c20d4d9):
 * GET /api/v1/consents/{id} returns {@code ConsentServiceView}
 * (infrastructure/rest/dto/ConsentServiceView.java) and the scope names are the provider's
 * {@code Consent.SUPPORTED_SCOPES}. The fixtures below are copied from there; when the provider
 * changes, update them from its source and this test shows what breaks here.
 */
class ConsentServiceViewContractTest {

    /** Record components of the provider's ConsentServiceView, in declaration order. */
    static final List<String> PROVIDER_FIELDS = List.of(
            "consentId", "participantId", "customerId", "scopes", "accountIds", "status", "expiresAt", "usable");

    /** Provider Consent.SUPPORTED_SCOPES. */
    static final Set<String> PROVIDER_SCOPES = Set.of(
            "READACCOUNTS", "READBALANCES", "READTRANSACTIONS", "READBENEFICIARIES", "READDIRECTDEBITS",
            "READSTANDINGORDERS", "READPARTIES", "READSCHEDULEDPAYMENTS", "INITIATEPAYMENTS",
            "INITIATEBULKPAYMENTS", "INITIATEVRP", "READPOLICIES", "READPRODUCTS", "READATMS");

    /** An authorised bulk consent as the provider serialises it (scopes and accountIds sorted). */
    static final String AUTHORISED_BULK_CONSENT = """
            {"consentId":"CONSENT-6F1C","participantId":"TPP-001","customerId":"CUST-1",
             "scopes":["INITIATEBULKPAYMENTS","READACCOUNTS"],"accountIds":["ACC-1"],
             "status":"AUTHORIZED","expiresAt":"2099-01-01T00:00:00Z","usable":true}
            """;

    private static final String BASE = "http://consent-authorization-service.open-finance.svc.cluster.local:8080";

    private final RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HttpBulkConsentAdapter adapter = new HttpBulkConsentAdapter(builder.build(), () -> "svc-token");

    @Test
    void theRequiredScopeIsOneTheProviderIssues() {
        assertThat(PROVIDER_SCOPES).contains(BulkConsentContext.INITIATE_BULK_PAYMENTS);
    }

    @Test
    void everyFieldThisServiceReadsIsInTheProvidersView() {
        List<String> read = Arrays.stream(HttpBulkConsentAdapter.ConsentResponse.class.getRecordComponents())
                .map(component -> component.getName())
                .toList();

        assertThat(PROVIDER_FIELDS).containsAll(read);
    }

    @Test
    void theProvidersAuthorisedBulkConsentAllowsAFile() {
        server.expect(requestTo(BASE + "/api/v1/consents/CONSENT-6F1C"))
                .andRespond(withSuccess(AUTHORISED_BULK_CONSENT, MediaType.APPLICATION_JSON));

        BulkConsentContext consent = adapter.findById("CONSENT-6F1C").orElseThrow();

        assertThat(consent.allowsBulkInitiation()).isTrue();
        assertThat(consent.isAuthorized()).isTrue();
        assertThat(consent.belongsToTpp("TPP-001")).isTrue();
        server.verify();
    }

    @Test
    void aSinglePaymentConsentDoesNotAllowAFile() {
        server.expect(requestTo(BASE + "/api/v1/consents/CONSENT-7A2D"))
                .andRespond(withSuccess(AUTHORISED_BULK_CONSENT.replace("CONSENT-6F1C", "CONSENT-7A2D")
                        .replace("\"INITIATEBULKPAYMENTS\",", "\"INITIATEPAYMENTS\","), MediaType.APPLICATION_JSON));

        assertThat(adapter.findById("CONSENT-7A2D").orElseThrow().allowsBulkInitiation()).isFalse();
    }
}
