package com.enterprise.openfinance.bulkpayments.infrastructure.consent;

import com.enterprise.openfinance.bulkpayments.domain.exception.ForbiddenException;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkConsentContext;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkConsentPort;
import com.enterprise.openfinance.bulkpayments.infrastructure.rest.InteractionIdFilter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Anti-corruption adapter to consent-authorization-service
 * (fintechbankx-openfinance-consent-auth-service), internal read
 * GET /api/v1/consents/{consentId} (never the /open-finance/v1 TPP path).
 * Calls carry this service's own client-credentials token, never the TPP's.
 * The response's {@code usable} flag is authoritative. 404 means no consent;
 * any other failure, or a response without the fields read here, fails closed.
 */
public class HttpBulkConsentAdapter implements BulkConsentPort {

    static final String CONSENT_PATH = "/api/v1/consents/{consentId}";

    private final RestClient client;
    private final Supplier<String> serviceToken;

    public HttpBulkConsentAdapter(RestClient client, Supplier<String> serviceToken) {
        this.client = client;
        this.serviceToken = serviceToken;
    }

    @Override
    public Optional<BulkConsentContext> findById(String consentId) {
        ConsentResponse response;
        try {
            response = client.get()
                    .uri(CONSENT_PATH, consentId)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + serviceToken.get())
                    .header("X-FAPI-Interaction-ID", interactionId())
                    .retrieve()
                    .body(ConsentResponse.class);
        } catch (HttpClientErrorException exception) {
            if (exception.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                return Optional.empty();
            }
            throw new ConsentServiceUnavailableException("Consent service refused the lookup: "
                    + exception.getStatusCode().value(), exception);
        } catch (RestClientException | IllegalStateException exception) {
            throw new ConsentServiceUnavailableException("Consent service unavailable", exception);
        }
        if (response == null || response.consentId() == null || response.participantId() == null
                || response.expiresAt() == null || response.usable() == null) {
            throw new ConsentServiceUnavailableException("Consent service returned an incomplete consent", null);
        }
        if (response.scopes() == null || response.scopes().isEmpty()) {
            throw new ForbiddenException(ForbiddenException.CONSENT_NOT_USABLE);
        }
        return Optional.of(new BulkConsentContext(response.consentId(), response.participantId(), response.scopes(),
                response.expiresAt(), response.usable()));
    }

    private static String interactionId() {
        String fromRequest = MDC.get(InteractionIdFilter.MDC_KEY);
        return fromRequest != null ? fromRequest : UUID.randomUUID().toString();
    }

    /**
     * The fields this context reads from the minimal internal consent view
     * {consentId, participantId, customerId, scopes, accountIds, status, expiresAt, usable};
     * the others and any new field are ignored.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ConsentResponse(String consentId, String participantId, Set<String> scopes, String status,
                           Instant expiresAt, Boolean usable) {
    }
}
