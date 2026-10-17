package com.enterprise.openfinance.bulkpayments.infrastructure.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * Validates one DPoP proof (RFC 9449 section 4.3): typ dpop+jwt, PS256 or ES256,
 * a public JWK in the header that verifies the signature, htm and htu equal to the
 * request, iat inside the window, ath equal to the hash of the access token, and a
 * jti not seen before. The jti is registered last, so an invalid proof does not use
 * up its jti. Based on the request-to-pay DPoPValidationService.
 */
public class DpopProofValidator {

    static final String DPOP_TYPE = "dpop+jwt";
    static final Set<JWSAlgorithm> ALGORITHMS = Set.of(JWSAlgorithm.PS256, JWSAlgorithm.ES256);

    private final DpopJtiReplayStore replayStore;
    private final Clock clock;
    private final Duration maxAge;
    private final Duration allowedFutureSkew;

    public DpopProofValidator(DpopJtiReplayStore replayStore, Clock clock, Duration maxAge, Duration allowedFutureSkew) {
        this.replayStore = replayStore;
        this.clock = clock;
        this.maxAge = maxAge;
        this.allowedFutureSkew = allowedFutureSkew;
    }

    /** @return the proof's public key, for the cnf.jkt comparison */
    public JWK validate(String proof, String method, URI requestUri, String accessToken) {
        if (proof == null || proof.isBlank()) {
            throw new DpopValidationException("DPoP proof is missing");
        }
        SignedJWT jwt = parse(proof);
        JWK key = verifiedKey(jwt);
        JWTClaimsSet claims = claims(jwt);
        checkRequest(claims, method, requestUri);
        Instant iat = checkIssuedAt(claims);
        checkAccessTokenHash(claims, accessToken);
        registerJti(claims, iat);
        return key;
    }

    private static SignedJWT parse(String proof) {
        try {
            return SignedJWT.parse(proof);
        } catch (ParseException e) {
            throw new DpopValidationException("DPoP proof has an invalid format", e);
        }
    }

    private static JWK verifiedKey(SignedJWT jwt) {
        JWSHeader header = jwt.getHeader();
        if (!new JOSEObjectType(DPOP_TYPE).equals(header.getType())) {
            throw new DpopValidationException("DPoP proof typ must be dpop+jwt");
        }
        if (!ALGORITHMS.contains(header.getAlgorithm())) {
            throw new DpopValidationException("DPoP proof algorithm not allowed: " + header.getAlgorithm());
        }
        JWK key = header.getJWK();
        if (key == null) {
            throw new DpopValidationException("DPoP proof jwk header is missing");
        }
        if (key.isPrivate()) {
            throw new DpopValidationException("DPoP proof jwk must not contain a private key");
        }
        try {
            if (!jwt.verify(verifier(key))) {
                throw new DpopValidationException("DPoP proof signature does not verify");
            }
        } catch (JOSEException e) {
            throw new DpopValidationException("DPoP proof signature does not verify", e);
        }
        return key;
    }

    private static JWSVerifier verifier(JWK key) throws JOSEException {
        if (key instanceof ECKey ec) {
            return new ECDSAVerifier(ec);
        }
        if (key instanceof RSAKey rsa) {
            return new RSASSAVerifier(rsa);
        }
        throw new DpopValidationException("DPoP proof jwk type not supported: " + key.getKeyType());
    }

    private static JWTClaimsSet claims(SignedJWT jwt) {
        try {
            return jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw new DpopValidationException("DPoP proof claims are invalid", e);
        }
    }

    private static void checkRequest(JWTClaimsSet claims, String method, URI requestUri) {
        String htm = stringClaim(claims, "htm");
        if (htm == null || !htm.equals(method)) {
            throw new DpopValidationException("DPoP proof htm does not match the request method");
        }
        String htu = stringClaim(claims, "htu");
        if (htu == null || !normalise(URI.create(htu)).equals(normalise(requestUri))) {
            throw new DpopValidationException("DPoP proof htu does not match the request URL");
        }
    }

    private Instant checkIssuedAt(JWTClaimsSet claims) {
        if (claims.getIssueTime() == null) {
            throw new DpopValidationException("DPoP proof iat is missing");
        }
        Instant iat = claims.getIssueTime().toInstant();
        Instant now = clock.instant();
        if (iat.isAfter(now.plus(allowedFutureSkew)) || iat.isBefore(now.minus(maxAge))) {
            throw new DpopValidationException("DPoP proof iat is outside the accepted window");
        }
        return iat;
    }

    private static void checkAccessTokenHash(JWTClaimsSet claims, String accessToken) {
        String ath = stringClaim(claims, "ath");
        if (ath == null || accessToken == null || !ath.equals(hash(accessToken))) {
            throw new DpopValidationException("DPoP proof ath does not match the access token");
        }
    }

    private void registerJti(JWTClaimsSet claims, Instant iat) {
        String jti = claims.getJWTID();
        if (jti == null || jti.isBlank()) {
            throw new DpopValidationException("DPoP proof jti is missing");
        }
        if (!replayStore.registerIfAbsent(jti, iat.plus(maxAge).plus(allowedFutureSkew))) {
            throw new DpopValidationException("DPoP proof jti replay detected");
        }
    }

    private static String stringClaim(JWTClaimsSet claims, String name) {
        try {
            return claims.getStringClaim(name);
        } catch (ParseException e) {
            throw new DpopValidationException("DPoP proof " + name + " must be a string", e);
        }
    }

    /** Scheme and host lower case, default port dropped, query and fragment ignored (RFC 9449 section 4.3). */
    static String normalise(URI uri) {
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new DpopValidationException("DPoP proof htu does not match the request URL");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = port == -1 || ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port) + path;
    }

    static String hash(String accessToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(accessToken.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
