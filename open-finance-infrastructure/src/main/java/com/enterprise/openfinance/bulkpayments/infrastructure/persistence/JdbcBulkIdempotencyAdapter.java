package com.enterprise.openfinance.bulkpayments.infrastructure.persistence;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkFileStatus;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkIdempotencyRecord;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkIdempotencyPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * Upload idempotency in sc_pay_bulk_orchestration.bulk_idempotency. The key
 * is reserved with one atomic INSERT ... ON CONFLICT: PostgreSQL makes a
 * concurrent insert of the same key wait for the first transaction, so only
 * one upload per key and TPP can create a file. An expired key is taken over.
 */
@Repository
public class JdbcBulkIdempotencyAdapter implements BulkIdempotencyPort {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcBulkIdempotencyAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BulkIdempotencyRecord> find(String idempotencyKey, String tppId, Instant now) {
        return jdbc.query("""
                        select idempotency_key, tpp_id, request_hash, file_id, file_status, expires_at
                        from bulk_idempotency
                        where tpp_id = :tppId and idempotency_key = :key and expires_at > :now
                        """,
                new MapSqlParameterSource("tppId", tppId).addValue("key", idempotencyKey)
                        .addValue("now", Timestamp.from(now)),
                (rs, rowNum) -> new BulkIdempotencyRecord(
                        rs.getString("idempotency_key"),
                        rs.getString("tpp_id"),
                        rs.getString("request_hash"),
                        rs.getString("file_id"),
                        BulkFileStatus.valueOf(rs.getString("file_status")),
                        rs.getTimestamp("expires_at").toInstant()))
                .stream().findFirst();
    }

    @Override
    public boolean reserve(BulkIdempotencyRecord record, Instant now) {
        int rows = jdbc.update("""
                insert into bulk_idempotency (tpp_id, idempotency_key, request_hash, file_id, file_status, created_at, expires_at)
                values (:tppId, :key, :requestHash, :fileId, :status, :now, :expiresAt)
                on conflict (tpp_id, idempotency_key) do update
                    set request_hash = excluded.request_hash,
                        file_id = excluded.file_id,
                        file_status = excluded.file_status,
                        created_at = excluded.created_at,
                        expires_at = excluded.expires_at
                    where bulk_idempotency.expires_at <= :now
                """, new MapSqlParameterSource("tppId", record.tppId())
                .addValue("key", record.idempotencyKey())
                .addValue("requestHash", record.requestHash())
                .addValue("fileId", record.fileId())
                .addValue("status", record.status().name())
                .addValue("now", Timestamp.from(now))
                .addValue("expiresAt", Timestamp.from(record.expiresAt())));
        return rows == 1;
    }
}
