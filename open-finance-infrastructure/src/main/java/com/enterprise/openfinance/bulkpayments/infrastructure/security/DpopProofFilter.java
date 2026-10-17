package com.enterprise.openfinance.bulkpayments.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Runs after bearer-token authentication on the TPP API and applies
 * {@link DpopRequestVerifier}. A failure answers 401 with a DPoP challenge.
 */
public class DpopProofFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DpopProofFilter.class);

    private final DpopRequestVerifier verifier;
    private final String pathPrefix;

    public DpopProofFilter(DpopRequestVerifier verifier, String pathPrefix) {
        this.verifier = verifier;
        this.pathPrefix = pathPrefix;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(pathPrefix);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            verifier.verify(request, SecurityContextHolder.getContext().getAuthentication());
        } catch (DpopValidationException e) {
            log.info("DPoP rejected: {}", e.getMessage());
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                    "DPoP error=\"invalid_dpop_proof\", algs=\"ES256 PS256\"");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"code\":\"INVALID_DPOP_PROOF\",\"message\":\"Invalid or missing DPoP proof\","
                    + "\"interactionId\":\"" + interactionId(request) + "\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Echoed only when it is a plain token, so nothing can be injected into the JSON body. */
    private static String interactionId(HttpServletRequest request) {
        String value = request.getHeader("X-FAPI-Interaction-ID");
        return value != null && value.matches("[A-Za-z0-9._-]{1,128}") ? value : "";
    }
}
