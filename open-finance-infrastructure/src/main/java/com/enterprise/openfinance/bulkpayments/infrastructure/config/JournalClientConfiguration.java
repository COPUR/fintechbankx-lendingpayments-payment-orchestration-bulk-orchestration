package com.enterprise.openfinance.bulkpayments.infrastructure.config;

import com.enterprise.openfinance.bulkpayments.domain.port.out.JournalEntryPort;
import com.enterprise.openfinance.bulkpayments.infrastructure.ledger.HttpJournalEntryAdapter;
import com.enterprise.openfinance.bulkpayments.infrastructure.ledger.InMemoryJournalEntryAdapter;
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
 * Journal postings to the accounts ledger (svc-acc-accounts-ledger, ADR-030). Default:
 * POST /api/v1/journal-entries over HTTP with this service's own client-credentials
 * token. {@code journal.adapter=in-memory} keeps bookings in memory for local runs
 * and tests only. No use case calls the port yet; this wires the adapter only.
 */
@Configuration
public class JournalClientConfiguration {

    @Configuration
    @ConditionalOnProperty(name = "journal.adapter", havingValue = "http", matchIfMissing = true)
    static class HttpJournal {

        @Bean
        JournalEntryPort httpJournalEntryAdapter(
                RestClient.Builder builder,
                OAuth2AuthorizedClientManager serviceAuthorizedClientManager,
                @Value("${journal.base-url}") String baseUrl,
                @Value("${journal.client-registration-id:consent-service}") String registrationId,
                @Value("${journal.connect-timeout:PT1S}") Duration connectTimeout,
                @Value("${journal.read-timeout:PT2S}") Duration readTimeout) {
            ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                    .withConnectTimeout(connectTimeout)
                    .withReadTimeout(readTimeout);
            RestClient client = builder
                    .baseUrl(baseUrl)
                    .requestFactory(ClientHttpRequestFactories.get(settings))
                    .build();
            return new HttpJournalEntryAdapter(client,
                    ServiceTokenConfiguration.serviceToken(serviceAuthorizedClientManager, registrationId));
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "journal.adapter", havingValue = "in-memory")
    static class InMemoryJournal {

        @Bean
        JournalEntryPort inMemoryJournalEntryAdapter() {
            return new InMemoryJournalEntryAdapter();
        }
    }
}
