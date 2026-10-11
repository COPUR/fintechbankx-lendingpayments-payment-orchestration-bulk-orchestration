package com.enterprise.openfinance.bulkpayments.infrastructure.config;

import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkConsentPort;
import com.enterprise.openfinance.bulkpayments.infrastructure.consent.HttpBulkConsentAdapter;
import com.enterprise.openfinance.bulkpayments.infrastructure.consent.InMemoryBulkConsentAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Consent reads. Default: the consent service over HTTP with this service's
 * own client-credentials token (Keycloak client svc-pay-bulk-orchestration).
 * {@code openfinance.bulkpayments.consent.adapter=in-memory} seeds demo
 * consents for local runs and tests only.
 */
@Configuration
public class ConsentClientConfiguration {

    static final String SERVICE_PRINCIPAL = "svc-pay-bulk-orchestration";

    @Configuration
    @ConditionalOnProperty(name = "openfinance.bulkpayments.consent.adapter", havingValue = "http", matchIfMissing = true)
    static class HttpConsent {

        @Bean
        OAuth2AuthorizedClientManager serviceAuthorizedClientManager(ClientRegistrationRepository registrations,
                                                                     OAuth2AuthorizedClientService authorizedClients) {
            AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                    new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, authorizedClients);
            manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                    .clientCredentials()
                    .build());
            return manager;
        }

        @Bean
        BulkConsentPort httpBulkConsentAdapter(
                RestClient.Builder builder,
                OAuth2AuthorizedClientManager serviceAuthorizedClientManager,
                @Value("${openfinance.bulkpayments.consent.base-url}") String baseUrl,
                @Value("${openfinance.bulkpayments.consent.client-registration-id:consent-service}") String registrationId,
                @Value("${openfinance.bulkpayments.consent.connect-timeout:PT1S}") Duration connectTimeout,
                @Value("${openfinance.bulkpayments.consent.read-timeout:PT2S}") Duration readTimeout) {
            ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                    .withConnectTimeout(connectTimeout)
                    .withReadTimeout(readTimeout);
            RestClient client = builder
                    .baseUrl(baseUrl)
                    .requestFactory(ClientHttpRequestFactories.get(settings))
                    .build();
            return new HttpBulkConsentAdapter(client, serviceToken(serviceAuthorizedClientManager, registrationId));
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "openfinance.bulkpayments.consent.adapter", havingValue = "in-memory")
    static class InMemoryConsent {

        @Bean
        BulkConsentPort inMemoryBulkConsentAdapter() {
            return new InMemoryBulkConsentAdapter();
        }
    }

    /**
     * Client-credentials token for this service. The manager caches the token
     * and fetches a new one only when it is about to expire.
     */
    static Supplier<String> serviceToken(OAuth2AuthorizedClientManager manager, String registrationId) {
        OAuth2AuthorizeRequest request = OAuth2AuthorizeRequest.withClientRegistrationId(registrationId)
                .principal(SERVICE_PRINCIPAL)
                .build();
        return () -> {
            OAuth2AuthorizedClient client = manager.authorize(request);
            if (client == null) {
                throw new IllegalStateException("No service token for client registration " + registrationId);
            }
            return client.getAccessToken().getTokenValue();
        };
    }
}
