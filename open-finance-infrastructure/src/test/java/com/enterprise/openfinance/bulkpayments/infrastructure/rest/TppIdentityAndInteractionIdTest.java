package com.enterprise.openfinance.bulkpayments.infrastructure.rest;

import com.enterprise.openfinance.bulkpayments.domain.exception.ForbiddenException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TppIdentityAndInteractionIdTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void tokenNamesTheTppAndAMismatchingHeaderIsForbidden() {
        authenticate(Jwt.withTokenValue("t").header("alg", "none").claim("azp", "TPP-001").build());

        assertThat(TppIdentityResolver.resolve(null)).isEqualTo("TPP-001");
        assertThat(TppIdentityResolver.resolve("TPP-001")).isEqualTo("TPP-001");
        assertThatThrownBy(() -> TppIdentityResolver.resolve("TPP-999"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void theTppIsTheAuthorisedPartyNeverATppIdClaim() {
        // Platform tokens name the TPP's OAuth client in azp; a tpp_id claim is not trusted.
        authenticate(Jwt.withTokenValue("t").header("alg", "none")
                .claim("azp", "TPP-001").claim("tpp_id", "TPP-002").build());

        assertThat(TppIdentityResolver.resolve(" ")).isEqualTo("TPP-001");
        assertThatThrownBy(() -> TppIdentityResolver.resolve("TPP-002")).isInstanceOf(ForbiddenException.class);

        authenticate(Jwt.withTokenValue("t").header("alg", "none").claim("tpp_id", "TPP-002").build());
        assertThatThrownBy(() -> TppIdentityResolver.resolve(null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Access token names no TPP (azp)");
    }

    @Test
    void tokenWithoutTppIsForbiddenAndNoTokenNeedsTheHeader() {
        authenticate(Jwt.withTokenValue("t").header("alg", "none").subject("someone").build());
        assertThatThrownBy(() -> TppIdentityResolver.resolve("TPP-001")).isInstanceOf(ForbiddenException.class);

        SecurityContextHolder.clearContext();
        assertThat(TppIdentityResolver.resolve(" TPP-003 ")).isEqualTo("TPP-003");
        assertThatThrownBy(() -> TppIdentityResolver.resolve(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void interactionIdIsPutInTheMdcForTheRequestOnly() throws Exception {
        InteractionIdFilter filter = new InteractionIdFilter();
        AtomicReference<String> seen = new AtomicReference<>();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-FAPI-Interaction-ID", "ix-123");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seen.set(MDC.get(InteractionIdFilter.MDC_KEY));
            }
        });

        assertThat(seen.get()).isEqualTo("ix-123");
        assertThat(MDC.get(InteractionIdFilter.MDC_KEY)).isNull();

        MockHttpServletRequest unsafe = new MockHttpServletRequest();
        unsafe.addHeader("X-FAPI-Interaction-ID", "bad value\nwith newline");
        filter.doFilter(unsafe, new MockHttpServletResponse(), new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seen.set(MDC.get(InteractionIdFilter.MDC_KEY));
            }
        });
        assertThat(seen.get()).isNotBlank().doesNotContain("\n");
    }

    private static void authenticate(Jwt jwt) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
