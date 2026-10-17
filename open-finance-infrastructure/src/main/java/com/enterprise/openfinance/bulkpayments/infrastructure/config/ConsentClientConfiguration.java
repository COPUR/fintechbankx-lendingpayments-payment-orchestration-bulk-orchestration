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
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Consent reads. Default: the consent service over HTTP with this service's
 * own client-credentials token ({@link ServiceTokenConfiguration}).
 * {@code openfinance.bulkpayments.consent.adapter=in-memory} seeds demo
 * consents for local runs and tests only.
 */
@Configuration
public class ConsentClientConfiguration {

    @Configuration
    @ConditionalOnProperty(name = "openfinance.bulkpayments.consent.adapter", havingValue = "http", matchIfMissing = true)
    static class HttpConsent {

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
            return new HttpBulkConsentAdapter(client,
                    ServiceTokenConfiguration.serviceToken(serviceAuthorizedClientManager, registrationId));
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
}
