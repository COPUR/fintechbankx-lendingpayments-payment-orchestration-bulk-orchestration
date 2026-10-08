package com.enterprise.openfinance.bulkpayments.infrastructure.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/** Builds RFC 9449 DPoP proofs for tests. */
final class DpopTestProofs {

    private DpopTestProofs() {
    }

    static ECKey newKey() {
        try {
            return new ECKeyGenerator(Curve.P_256).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String thumbprint(JWK key) {
        try {
            return key.computeThumbprint("SHA-256").toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String ath(String accessToken) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(accessToken.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A proof whose jwk header carries the private key; Nimbus refuses to build one, so it is assembled by hand. */
    static String withPrivateJwkHeader(ECKey key, String htu, Instant iat) {
        try {
            String header = "{\"typ\":\"dpop+jwt\",\"alg\":\"ES256\",\"jwk\":" + key.toJSONString() + "}";
            String payload = new JWTClaimsSet.Builder().claim("htm", "GET").claim("htu", htu)
                    .issueTime(Date.from(iat)).jwtID(UUID.randomUUID().toString()).build().toString();
            String input = b64(header) + "." + b64(payload);
            byte[] signature = new ECDSASigner(key).sign(
                    com.nimbusds.jose.JWSHeader.parse("{\"alg\":\"ES256\"}"), input.getBytes(StandardCharsets.US_ASCII))
                    .decode();
            return input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    static Builder proof(ECKey key) {
        return new Builder(key);
    }

    static final class Builder {
        private final ECKey signingKey;
        private JWK headerKey;
        private String typ = "dpop+jwt";
        private String htm = "GET";
        private String htu = "https://api.example.com/open-finance/v1/file-payments/FILE-1";
        private Instant iat = Instant.parse("2026-10-08T10:00:00Z");
        private String jti = UUID.randomUUID().toString();
        private String ath;

        private Builder(ECKey key) {
            this.signingKey = key;
            this.headerKey = key.toPublicJWK();
        }

        Builder htm(String value) { this.htm = value; return this; }
        Builder htu(String value) { this.htu = value; return this; }
        Builder iat(Instant value) { this.iat = value; return this; }
        Builder jti(String value) { this.jti = value; return this; }
        Builder ath(String value) { this.ath = value; return this; }
        Builder typ(String value) { this.typ = value; return this; }
        Builder headerKey(JWK value) { this.headerKey = value; return this; }

        String sign() {
            try {
                JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
                        .type(typ == null ? null : new JOSEObjectType(typ)).jwk(headerKey).build();
                JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                        .claim("htm", htm).claim("htu", htu)
                        .issueTime(iat == null ? null : Date.from(iat)).jwtID(jti);
                if (ath != null) {
                    claims.claim("ath", ath);
                }
                SignedJWT jwt = new SignedJWT(header, claims.build());
                jwt.sign(new ECDSASigner(signingKey));
                return jwt.serialize();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
