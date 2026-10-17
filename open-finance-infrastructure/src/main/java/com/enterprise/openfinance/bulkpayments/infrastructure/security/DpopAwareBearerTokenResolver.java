package com.enterprise.openfinance.bulkpayments.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

/**
 * Reads the access token from {@code Authorization: DPoP <token>} as well as
 * {@code Bearer <token>} (Spring Security 6.3 only understands Bearer).
 */
public class DpopAwareBearerTokenResolver implements BearerTokenResolver {

    private static final String DPOP_PREFIX = "DPoP ";
    private final DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();

    @Override
    public String resolve(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, DPOP_PREFIX, 0, DPOP_PREFIX.length())) {
            String token = authorization.substring(DPOP_PREFIX.length()).trim();
            return token.isEmpty() ? null : token;
        }
        return delegate.resolve(request);
    }
}
