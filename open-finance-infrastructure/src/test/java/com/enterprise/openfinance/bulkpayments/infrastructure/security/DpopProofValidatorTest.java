package com.enterprise.openfinance.bulkpayments.infrastructure.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DpopProofValidatorTest {

    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");
    private static final URI URL = URI.create("https://api.example.com/open-finance/v1/file-payments/FILE-1");
    private static final String TOKEN = "access-token-1";

    private final Set<String> seen = new HashSet<>();
    private final DpopJtiReplayStore replayStore = (jti, expiresAt) -> seen.add(jti);
    private final DpopProofValidator validator = new DpopProofValidator(replayStore,
            Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5), Duration.ofSeconds(60));
    private final ECKey key = DpopTestProofs.newKey();

    private DpopTestProofs.Builder valid() {
        return DpopTestProofs.proof(key).ath(DpopTestProofs.ath(TOKEN)).iat(NOW);
    }

    @Test
    void acceptsAValidProofAndReturnsItsPublicKey() {
        JWK jwk = validator.validate(valid().sign(), "GET", URL, TOKEN);

        assertThat(DpopTestProofs.thumbprint(jwk)).isEqualTo(DpopTestProofs.thumbprint(key));
        assertThat(jwk.isPrivate()).isFalse();
    }

    @Test
    void comparesHtuWithoutQueryAndCaseOfSchemeAndHost() {
        String proof = valid().htu("HTTPS://API.example.com:443/open-finance/v1/file-payments/FILE-1").sign();

        validator.validate(proof, "GET", URI.create(URL + "?page=2"), TOKEN);
    }

    @Test
    void rejectsMissingOrMalformedProofs() {
        assertRejected(null, "missing");
        assertRejected("  ", "missing");
        assertRejected("not-a-jwt", "format");
    }

    @Test
    void rejectsWrongTypeAndPrivateKeyInHeader() {
        assertRejected(valid().typ("JWT").sign(), "typ");
        assertThatThrownBy(() -> validator.validate(DpopTestProofs.withPrivateJwkHeader(key, URL.toString(), NOW),
                "GET", URL, TOKEN))
                .isInstanceOf(DpopValidationException.class)
                .hasMessageMatching(".*(private key|invalid format).*");
    }

    @Test
    void rejectsUnsupportedAlgorithm() throws Exception {
        RSAKey rsa = new RSAKeyGenerator(2048).generate();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(new JOSEObjectType("dpop+jwt")).jwk(rsa.toPublicJWK()).build(),
                new JWTClaimsSet.Builder().claim("htm", "GET").claim("htu", URL.toString())
                        .issueTime(Date.from(NOW)).jwtID("j-rs").claim("ath", DpopTestProofs.ath(TOKEN)).build());
        jwt.sign(new RSASSASigner(rsa));

        assertRejected(jwt.serialize(), "algorithm");
    }

    @Test
    void rejectsASignatureThatDoesNotMatchTheEmbeddedKey() {
        String proof = valid().headerKey(DpopTestProofs.newKey().toPublicJWK()).sign();

        assertRejected(proof, "signature");
    }

    @Test
    void rejectsMethodAndUrlMismatches() {
        assertRejected(valid().htm("POST").sign(), "htm");
        assertRejected(valid().htu("https://api.example.com/open-finance/v1/file-payments/FILE-2").sign(), "htu");
        assertRejected(valid().htu("https://evil.example.com/open-finance/v1/file-payments/FILE-1").sign(), "htu");
    }

    @Test
    void enforcesTheIatWindow() {
        validator.validate(valid().iat(NOW.minusSeconds(299)).sign(), "GET", URL, TOKEN);
        validator.validate(valid().iat(NOW.plusSeconds(59)).sign(), "GET", URL, TOKEN);

        assertRejected(valid().iat(NOW.minusSeconds(301)).sign(), "iat");
        assertRejected(valid().iat(NOW.plusSeconds(61)).sign(), "iat");
        assertRejected(valid().iat(null).sign(), "iat");
    }

    @Test
    void bindsTheProofToTheAccessToken() {
        assertRejected(valid().ath(null).sign(), "ath");
        assertRejected(valid().ath(DpopTestProofs.ath("another-token")).sign(), "ath");
    }

    @Test
    void rejectsAMissingJtiAndAReplayedJti() {
        assertRejected(valid().jti(null).sign(), "jti");

        String proof = valid().jti("jti-replay").sign();
        validator.validate(proof, "GET", URL, TOKEN);
        assertRejected(proof, "replay");
    }

    @Test
    void anInvalidProofDoesNotConsumeItsJti() {
        assertRejected(valid().jti("jti-kept").htm("DELETE").sign(), "htm");

        validator.validate(valid().jti("jti-kept").sign(), "GET", URL, TOKEN);
        assertThat(seen).containsExactly("jti-kept");
    }

    private void assertRejected(String proof, String reason) {
        assertThatThrownBy(() -> validator.validate(proof, "GET", URL, TOKEN))
                .isInstanceOf(DpopValidationException.class)
                .hasMessageContaining(reason);
    }
}
