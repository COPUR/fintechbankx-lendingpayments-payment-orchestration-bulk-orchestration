package com.enterprise.openfinance.bulkpayments.infrastructure.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Applies DPoP to an authenticated request (platform contract: "DPoP applies by
 * caller, not by namespace"; this API is TPP-facing).
 * <ul>
 *   <li>required (default): Authorization scheme DPoP, exactly one valid proof,
 *       a token bound with cnf.jkt and the proof key's thumbprint equal to it;</li>
 *   <li>not required: a bound token still needs a matching proof and any proof
 *       sent is verified; a plain Bearer token without a proof passes.</li>
 * </ul>
 * Unauthenticated requests are left to the authorization rules (401).
 */
public class DpopRequestVerifier {

    private static final String DPOP_SCHEME = "DPoP ";

    private final DpopProofValidator validator;
    private final boolean required;

    public DpopRequestVerifier(DpopProofValidator validator, boolean required) {
        this.validator = validator;
        this.required = required;
    }

    public void verify(HttpServletRequest request, Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            return;
        }
        Jwt token = jwtAuthentication.getToken();
        String boundThumbprint = boundThumbprint(token);
        List<String> proofs = Collections.list(request.getHeaders("DPoP"));
        if (proofs.size() > 1) {
            throw new DpopValidationException("Exactly one DPoP header is allowed");
        }
        String proof = proofs.isEmpty() ? null : proofs.get(0);

        if (required) {
            requireDpopScheme(request);
            if (boundThumbprint == null) {
                throw new DpopValidationException("Access token is not DPoP-bound (cnf.jkt missing)");
            }
        }
        if (proof == null || proof.isBlank()) {
            if (required || boundThumbprint != null) {
                throw new DpopValidationException("DPoP proof is required");
            }
            return;
        }
        JWK key = validator.validate(proof, request.getMethod(), URI.create(request.getRequestURL().toString()),
                token.getTokenValue());
        if (boundThumbprint == null) {
            throw new DpopValidationException("DPoP proof sent with a token that has no cnf.jkt");
        }
        if (!boundThumbprint.equals(thumbprint(key))) {
            throw new DpopValidationException("DPoP proof key thumbprint does not match cnf.jkt");
        }
    }

    private static void requireDpopScheme(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.regionMatches(true, 0, DPOP_SCHEME, 0, DPOP_SCHEME.length())) {
            throw new DpopValidationException("Authorization scheme must be DPoP");
        }
    }

    private static String boundThumbprint(Jwt token) {
        if (token.getClaims().get("cnf") instanceof Map<?, ?> cnf
                && cnf.get("jkt") instanceof String jkt && !jkt.isBlank()) {
            return jkt;
        }
        return null;
    }

    private static String thumbprint(JWK key) {
        try {
            return key.computeThumbprint("SHA-256").toString();
        } catch (JOSEException e) {
            throw new DpopValidationException("DPoP proof key thumbprint cannot be computed", e);
        }
    }
}
