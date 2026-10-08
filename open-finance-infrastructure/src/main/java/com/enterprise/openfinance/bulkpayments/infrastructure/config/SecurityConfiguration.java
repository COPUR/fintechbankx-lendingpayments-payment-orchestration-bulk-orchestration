package com.enterprise.openfinance.bulkpayments.infrastructure.config;

import com.enterprise.openfinance.bulkpayments.infrastructure.security.DpopAwareBearerTokenResolver;
import com.enterprise.openfinance.bulkpayments.infrastructure.security.DpopProofFilter;
import com.enterprise.openfinance.bulkpayments.infrastructure.security.DpopProofValidator;
import com.enterprise.openfinance.bulkpayments.infrastructure.security.DpopRequestVerifier;
import com.enterprise.openfinance.bulkpayments.infrastructure.security.JdbcDpopJtiReplayStore;
import com.enterprise.openfinance.bulkpayments.infrastructure.security.JwtValidation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import java.time.Clock;
import java.time.Duration;

/**
 * Stateless OAuth2 resource server. Access tokens come from the platform
 * Keycloak realm and must name this service in {@code aud}. The file-payments
 * API is TPP-facing, so DPoP is enforced (platform contract "DPoP applies by
 * caller, not by namespace"): DPoP scheme, a verified proof with a jti replay
 * guard, and the proof key matching the token's cnf.jkt. A Bearer token gets 401.
 * {@code openfinance.bulkpayments.security.dpop.required=false} relaxes this to
 * "verify when used". Actuator endpoints are served on the management port.
 */
@Configuration
@EnableScheduling
public class SecurityConfiguration {

    static final String TPP_API = "/open-finance/v1/file-payments";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, DpopRequestVerifier dpopVerifier) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers(TPP_API + "/**", TPP_API).authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(new DpopAwareBearerTokenResolver())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(JwtValidation.keycloakRealmRoles())))
                .addFilterAfter(new DpopProofFilter(dpopVerifier, TPP_API), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
                          @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
                          @Value("${openfinance.bulkpayments.security.audience:svc-pay-bulk-orchestration}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(JwtValidation.validator(issuer, audience));
        return decoder;
    }

    @Bean
    JdbcDpopJtiReplayStore dpopJtiReplayStore(NamedParameterJdbcTemplate jdbc) {
        return new JdbcDpopJtiReplayStore(jdbc);
    }

    @Bean
    DpopRequestVerifier dpopRequestVerifier(
            JdbcDpopJtiReplayStore replayStore, Clock clock,
            @Value("${openfinance.bulkpayments.security.dpop.required:true}") boolean required,
            @Value("${openfinance.bulkpayments.security.dpop.max-proof-age:PT5M}") Duration maxProofAge,
            @Value("${openfinance.bulkpayments.security.dpop.allowed-future-skew:PT1M}") Duration futureSkew) {
        return new DpopRequestVerifier(new DpopProofValidator(replayStore, clock, maxProofAge, futureSkew), required);
    }

    @Bean
    DpopReplayPurge dpopReplayPurge(JdbcDpopJtiReplayStore replayStore, Clock clock) {
        return new DpopReplayPurge(replayStore, clock);
    }

    /** Removes jti rows whose proofs can no longer pass the iat window. */
    static class DpopReplayPurge {
        private final JdbcDpopJtiReplayStore replayStore;
        private final Clock clock;

        DpopReplayPurge(JdbcDpopJtiReplayStore replayStore, Clock clock) {
            this.replayStore = replayStore;
            this.clock = clock;
        }

        @Scheduled(fixedDelayString = "${openfinance.bulkpayments.security.dpop.purge-interval:PT5M}")
        int purge() {
            return replayStore.purgeExpired(clock.instant());
        }
    }
}
