package com.enterprise.openfinance.bulkpayments.infrastructure.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Token rules shared by the resource server: issuer, expiry, audience and Keycloak realm roles. */
public final class JwtValidation {

    private JwtValidation() {
    }

    /** Default checks (expiry, not-before), the issuer, and {@code aud} containing {@code audience}. */
    public static OAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
        if (audience == null || audience.isBlank()) {
            throw new IllegalStateException("The token audience (OIDC_AUDIENCE) must be configured");
        }
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
                audienceValidator(audience));
    }

    static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        return token -> token.getAudience() != null && token.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                        "The token is not issued for " + audience, null));
    }

    /** Keycloak realm roles (lowercase) become ROLE_* authorities, per the platform contract. */
    public static Converter<Jwt, AbstractAuthenticationToken> keycloakRealmRoles() {
        return jwt -> new JwtAuthenticationToken(jwt, realmRoles(jwt), jwt.getSubject());
    }

    @SuppressWarnings("unchecked")
    static Collection<GrantedAuthority> realmRoles(Jwt jwt) {
        Object realmAccess = jwt.getClaims().get("realm_access");
        if (!(realmAccess instanceof Map<?, ?> access) || !(access.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return ((Collection<Object>) roles).stream()
                .map(String::valueOf)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.ROOT)))
                .toList();
    }
}
