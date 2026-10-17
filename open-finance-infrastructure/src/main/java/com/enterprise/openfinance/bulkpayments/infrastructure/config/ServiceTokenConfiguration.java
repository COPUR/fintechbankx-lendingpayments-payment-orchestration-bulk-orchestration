package com.enterprise.openfinance.bulkpayments.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

import java.util.function.Supplier;

/**
 * This service's own client-credentials token (Keycloak client svc-pay-bulk-orchestration)
 * for outbound calls: the consent service and the accounts ledger. The audiences a token
 * carries are the IdP's audience mappers for this client, not something chosen here.
 * Present as soon as one HTTP adapter is configured.
 */
@Configuration
@ConditionalOnExpression("'${openfinance.bulkpayments.consent.adapter:http}' == 'http' or '${journal.adapter:http}' == 'http'")
public class ServiceTokenConfiguration {

    static final String SERVICE_PRINCIPAL = "svc-pay-bulk-orchestration";

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
