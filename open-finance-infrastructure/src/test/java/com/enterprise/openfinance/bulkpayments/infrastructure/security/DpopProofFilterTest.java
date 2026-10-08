package com.enterprise.openfinance.bulkpayments.infrastructure.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DpopProofFilterTest {

    private final DpopProofFilter filter = new DpopProofFilter(new DpopRequestVerifier(
            new DpopProofValidator((jti, exp) -> true, Clock.systemUTC(), Duration.ofMinutes(5), Duration.ofMinutes(1)),
            true), "/open-finance/v1/file-payments");

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void answers401WithADpopChallengeWhenTheProofIsMissing() throws Exception {
        authenticate();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/open-finance/v1/file-payments/F-1");
        request.addHeader("Authorization", "DPoP tok");
        request.addHeader("X-FAPI-Interaction-ID", "ix-9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).startsWith("DPoP error=\"invalid_dpop_proof\"");
        assertThat(response.getContentAsString()).contains("\"code\":\"INVALID_DPOP_PROOF\"").contains("ix-9");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void ignoresPathsOutsideTheTppApi() throws Exception {
        authenticate();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    private static void authenticate() {
        Jwt jwt = Jwt.withTokenValue("tok").header("alg", "RS256").subject("c")
                .claim("cnf", Map.of("jkt", "x")).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
