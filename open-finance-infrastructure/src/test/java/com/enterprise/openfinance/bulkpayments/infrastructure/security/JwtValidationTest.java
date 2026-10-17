package com.enterprise.openfinance.bulkpayments.infrastructure.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtValidationTest {

    private static final String ISSUER = "https://identity.example.internal/realms/fintechbankx";
    private static final String AUDIENCE = "svc-pay-bulk-orchestration";

    private final RSAKey key = generate();
    private final NimbusJwtDecoder decoder = decoder();

    @Test
    void acceptsATokenIssuedForThisService() throws Exception {
        Jwt jwt = decoder.decode(token(ISSUER, List.of("account", AUDIENCE)));

        assertThat(jwt.getAudience()).contains(AUDIENCE);
    }

    @Test
    void rejectsATokenIssuedForAnotherService() throws Exception {
        String forPayments = token(ISSUER, List.of("svc-pay-initiation-settlement"));

        assertThatThrownBy(() -> decoder.decode(forPayments))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("not issued for svc-pay-bulk-orchestration");
    }

    @Test
    void rejectsATokenWithoutAudienceOrFromAnotherIssuer() throws Exception {
        String noAudience = token(ISSUER, List.of());
        String otherIssuer = token("https://evil.example/realms/x", List.of(AUDIENCE));

        assertThatThrownBy(() -> decoder.decode(noAudience)).isInstanceOf(JwtValidationException.class);
        assertThatThrownBy(() -> decoder.decode(otherIssuer)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void audienceMustBeConfigured() {
        assertThatThrownBy(() -> JwtValidation.validator(ISSUER, " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OIDC_AUDIENCE");
    }

    @Test
    void realmRolesBecomeUpperCaseRoleAuthorities() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("svc-x")
                .claim("realm_access", Map.of("roles", List.of("service", "banker")))
                .build();
        Jwt noRoles = Jwt.withTokenValue("t").header("alg", "none").subject("svc-x").build();

        assertThat(JwtValidation.keycloakRealmRoles().convert(jwt).getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_SERVICE", "ROLE_BANKER");
        assertThat(JwtValidation.realmRoles(noRoles)).isEmpty();
    }

    private String token(String issuer, List<String> audience) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("tpp-client")
                .claim("azp", "TPP-001")
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(300)));
        if (!audience.isEmpty()) {
            claims.audience(audience);
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private NimbusJwtDecoder decoder() {
        try {
            NimbusJwtDecoder nimbus = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
            nimbus.setJwtValidator(JwtValidation.validator(ISSUER, AUDIENCE));
            return nimbus;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey generate() {
        try {
            return new RSAKeyGenerator(2048).keyID("test").generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
