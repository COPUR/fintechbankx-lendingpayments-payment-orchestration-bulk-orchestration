package com.enterprise.openfinance.bulkpayments.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ServiceTokenConfigurationTest {

    private static final ClientRegistration REGISTRATION = ClientRegistration.withRegistrationId("consent-service")
            .clientId("svc-pay-bulk-orchestration")
            .clientSecret("test-only")
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .tokenUri("http://idp/token")
            .build();

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(ServiceTokenConfiguration.class)
            .withBean(ClientRegistrationRepository.class, () -> mock(ClientRegistrationRepository.class))
            .withBean(OAuth2AuthorizedClientService.class, () -> mock(OAuth2AuthorizedClientService.class));

    @Test
    void serviceTokenIsThisServicesClientCredentialsToken() {
        AtomicReference<OAuth2AuthorizeRequest> seen = new AtomicReference<>();
        OAuth2AuthorizedClientManager manager = request -> {
            seen.set(request);
            OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                    "svc-token", Instant.now(), Instant.now().plusSeconds(300));
            return new OAuth2AuthorizedClient(REGISTRATION, request.getPrincipal().getName(), token);
        };

        Supplier<String> token = ServiceTokenConfiguration.serviceToken(manager, "consent-service");

        assertThat(token.get()).isEqualTo("svc-token");
        assertThat(seen.get().getClientRegistrationId()).isEqualTo("consent-service");
        assertThat(seen.get().getPrincipal().getName()).isEqualTo("svc-pay-bulk-orchestration");
    }

    @Test
    void missingServiceTokenFailsTheCall() {
        Supplier<String> token = ServiceTokenConfiguration.serviceToken(request -> null, "consent-service");

        assertThatThrownBy(token::get)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("consent-service");
    }

    @Test
    void oneTokenManagerServesEveryHttpAdapter() {
        context.run(ctx -> assertThat(ctx).hasSingleBean(OAuth2AuthorizedClientManager.class));
        context.withPropertyValues("openfinance.bulkpayments.consent.adapter=in-memory", "journal.adapter=http")
                .run(ctx -> assertThat(ctx).hasSingleBean(OAuth2AuthorizedClientManager.class));
        context.withPropertyValues("openfinance.bulkpayments.consent.adapter=http", "journal.adapter=in-memory")
                .run(ctx -> assertThat(ctx).hasSingleBean(OAuth2AuthorizedClientManager.class));
    }

    @Test
    void noTokenManagerWhenEveryAdapterIsInMemory() {
        context.withPropertyValues("openfinance.bulkpayments.consent.adapter=in-memory", "journal.adapter=in-memory")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(OAuth2AuthorizedClientManager.class));
    }
}
