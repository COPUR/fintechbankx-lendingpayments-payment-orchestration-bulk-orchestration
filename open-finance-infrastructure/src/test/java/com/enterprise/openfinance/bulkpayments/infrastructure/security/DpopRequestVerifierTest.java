package com.enterprise.openfinance.bulkpayments.infrastructure.security;

import com.nimbusds.jose.jwk.ECKey;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DpopRequestVerifierTest {

    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");
    private static final String URL = "https://api.example.com/open-finance/v1/file-payments/FILE-1";
    private static final String TOKEN = "access-token-1";

    private final Set<String> seen = new HashSet<>();
    private final DpopProofValidator validator = new DpopProofValidator((jti, exp) -> seen.add(jti),
            Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5), Duration.ofSeconds(60));
    private final DpopRequestVerifier required = new DpopRequestVerifier(validator, true);
    private final DpopRequestVerifier optional = new DpopRequestVerifier(validator, false);
    private final ECKey key = DpopTestProofs.newKey();

    @Test
    void requiredModeAcceptsABoundTokenWithAMatchingProof() {
        MockHttpServletRequest request = request("DPoP", proof(key));

        assertThatCode(() -> required.verify(request, token(DpopTestProofs.thumbprint(key)))).doesNotThrowAnyException();
        assertThat(seen).hasSize(1);
    }

    @Test
    void requiredModeRejectsAMissingProof() {
        assertRejected(required, request("DPoP", null), token(DpopTestProofs.thumbprint(key)), "required");
    }

    @Test
    void requiredModeRejectsAnUnboundTokenOrTheBearerScheme() {
        assertRejected(required, request("DPoP", proof(key)), token(null), "cnf");
        assertRejected(required, request("Bearer", proof(key)), token(DpopTestProofs.thumbprint(key)), "scheme");
    }

    @Test
    void rejectsAProofSignedByAnotherKeyThanTheTokenIsBoundTo() {
        ECKey other = DpopTestProofs.newKey();

        assertRejected(required, request("DPoP", proof(other)), token(DpopTestProofs.thumbprint(key)), "thumbprint");
    }

    @Test
    void rejectsMoreThanOneDpopHeader() {
        MockHttpServletRequest request = request("DPoP", proof(key));
        request.addHeader("DPoP", proof(key));

        assertRejected(required, request, token(DpopTestProofs.thumbprint(key)), "one DPoP");
    }

    @Test
    void optionalModeStillVerifiesBoundTokensAndProofs() {
        assertThatCode(() -> optional.verify(request("Bearer", null), token(null))).doesNotThrowAnyException();
        assertRejected(optional, request("DPoP", null), token(DpopTestProofs.thumbprint(key)), "required");
        assertRejected(optional, request("Bearer", "garbage"), token(null), "format");
        assertThatCode(() -> optional.verify(request("DPoP", proof(key)), token(DpopTestProofs.thumbprint(key))))
                .doesNotThrowAnyException();
    }

    @Test
    void leavesUnauthenticatedRequestsToTheAuthorizationRules() {
        assertThatCode(() -> required.verify(request("DPoP", null), null)).doesNotThrowAnyException();
        assertThatCode(() -> required.verify(request("DPoP", null), new TestingAuthenticationToken("u", "p")))
                .doesNotThrowAnyException();
    }

    private static MockHttpServletRequest request(String scheme, String proof) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/open-finance/v1/file-payments/FILE-1");
        request.setScheme("https");
        request.setServerName("api.example.com");
        request.setServerPort(443);
        request.addHeader("Authorization", scheme + " " + TOKEN);
        if (proof != null) {
            request.addHeader("DPoP", proof);
        }
        return request;
    }

    private static String proof(ECKey key) {
        return DpopTestProofs.proof(key).htu(URL).iat(NOW).ath(DpopTestProofs.ath(TOKEN)).sign();
    }

    private static JwtAuthenticationToken token(String jkt) {
        Jwt.Builder jwt = Jwt.withTokenValue(TOKEN).header("alg", "RS256").subject("client")
                .issuedAt(NOW).expiresAt(NOW.plusSeconds(300));
        if (jkt != null) {
            jwt.claim("cnf", Map.of("jkt", jkt));
        }
        return new JwtAuthenticationToken(jwt.build());
    }

    private static void assertRejected(DpopRequestVerifier verifier, MockHttpServletRequest request,
                                       org.springframework.security.core.Authentication auth, String reason) {
        assertThatThrownBy(() -> verifier.verify(request, auth))
                .isInstanceOf(DpopValidationException.class)
                .hasMessageContaining(reason);
    }
}
