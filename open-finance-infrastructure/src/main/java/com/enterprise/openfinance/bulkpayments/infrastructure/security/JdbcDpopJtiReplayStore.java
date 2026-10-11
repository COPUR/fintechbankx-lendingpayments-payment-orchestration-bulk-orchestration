package com.enterprise.openfinance.bulkpayments.infrastructure.security;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Replay guard in sc_pay_bulk_orchestration.dpop_proof_jti (Flyway V3), shared by
 * every replica. The primary key on the SHA-256 of the jti makes the first insert
 * win; a second insert of the same jti is a replay. Ported from request-to-pay.
 */
public class JdbcDpopJtiReplayStore implements DpopJtiReplayStore {

    static final String INSERT = """
            insert into dpop_proof_jti (jti_hash, expires_at) values (:jtiHash, :expiresAt)
            on conflict (jti_hash) do nothing
            """;
    static final String PURGE_EXPIRED = "delete from dpop_proof_jti where expires_at <= :now";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcDpopJtiReplayStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean registerIfAbsent(String jti, Instant expiresAt) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("jtiHash", sha256(jti))
                .addValue("expiresAt", Timestamp.from(expiresAt));
        return jdbc.update(INSERT, params) == 1;
    }

    /** @return number of expired rows removed */
    public int purgeExpired(Instant now) {
        return jdbc.update(PURGE_EXPIRED, new MapSqlParameterSource("now", Timestamp.from(now)));
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
