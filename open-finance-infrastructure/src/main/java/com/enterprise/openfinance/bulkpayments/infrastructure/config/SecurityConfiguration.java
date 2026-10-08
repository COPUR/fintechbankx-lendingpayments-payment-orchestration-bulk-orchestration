package com.enterprise.openfinance.bulkpayments.infrastructure.config;

import com.enterprise.openfinance.bulkpayments.infrastructure.security.DpopAwareBearerTokenResolver;
import com.enterprise.openfinance.bulkpayments.infrastructure.security.JwtValidation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless OAuth2 resource server. Access tokens come from the platform
 * Keycloak realm and must name this service in {@code aud}. The Authorization
 * scheme may be Bearer or DPoP; DPoP proof binding is not enforced for this
 * service (platform contract addendum 2026-10-08), the controller still
 * requires the DPoP header the FAPI profile defines. Actuator endpoints are
 * served on the management port.
 */
@Configuration
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/open-finance/v1/file-payments/**", "/open-finance/v1/file-payments").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(new DpopAwareBearerTokenResolver())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(JwtValidation.keycloakRealmRoles())));
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
}
