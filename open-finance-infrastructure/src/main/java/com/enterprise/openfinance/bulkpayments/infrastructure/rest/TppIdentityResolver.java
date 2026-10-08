package com.enterprise.openfinance.bulkpayments.infrastructure.rest;

import com.enterprise.openfinance.bulkpayments.domain.exception.ForbiddenException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The TPP a request acts for. With a validated access token the TPP is the
 * token's {@code tpp_id} claim, else its {@code azp} (the OAuth client); an
 * {@code x-fapi-financial-id} header that names another TPP is refused. The
 * header alone is accepted only without an authenticated token (unit tests).
 */
public final class TppIdentityResolver {

    static final String TPP_CLAIM = "tpp_id";

    private TppIdentityResolver() {
    }

    public static String resolve(String financialIdHeader) {
        String header = financialIdHeader == null || financialIdHeader.isBlank() ? null : financialIdHeader.trim();
        String fromToken = fromToken();
        if (fromToken == null) {
            if (header == null) {
                throw new IllegalArgumentException("x-fapi-financial-id header is required");
            }
            return header;
        }
        if (header != null && !header.equals(fromToken)) {
            throw new ForbiddenException("x-fapi-financial-id does not match the access token");
        }
        return fromToken;
    }

    private static String fromToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt)) {
            return null;
        }
        String tpp = jwt.getToken().getClaimAsString(TPP_CLAIM);
        if (tpp == null || tpp.isBlank()) {
            tpp = jwt.getToken().getClaimAsString("azp");
        }
        if (tpp == null || tpp.isBlank()) {
            throw new ForbiddenException("Access token names no TPP (tpp_id or azp)");
        }
        return tpp;
    }
}
