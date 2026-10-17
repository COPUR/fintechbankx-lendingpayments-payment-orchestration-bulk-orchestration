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
 * is reserved with one atomic INSERT ... ON CONFLICT DO NOTHING: PostgreSQL
 * makes a concurrent insert of the same key wait for the first transaction,
 * so only one upload per key and TPP can create a file. Keys are permanent
 * and never reusable: a key answers with its original file for good (V13
 * dropped expires_at; bulk_file also keeps (tpp_id, idempotency_key) unique).
 * Rows are deleted only with their file (foreign key, ON DELETE CASCADE).
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
                        select idempotency_key, tpp_id, request_hash, file_id, file_status
                        from bulk_idempotency
                        where tpp_id = :tppId and idempotency_key = :key
                        """,
                new MapSqlParameterSource("tppId", tppId).addValue("key", idempotencyKey),
                (rs, rowNum) -> new BulkIdempotencyRecord(
                        rs.getString("idempotency_key"),
                        rs.getString("tpp_id"),
                        rs.getString("request_hash"),
                        rs.getString("file_id"),
                        BulkFileStatus.valueOf(rs.getString("file_status"))))
                .stream().findFirst();
    }

    @Override
    public boolean reserve(BulkIdempotencyRecord record, Instant now) {
        int rows = jdbc.update("""
                insert into bulk_idempotency (tpp_id, idempotency_key, request_hash, file_id, file_status, created_at)
                values (:tppId, :key, :requestHash, :fileId, :status, :now)
                on conflict (tpp_id, idempotency_key) do nothing
                """, new MapSqlParameterSource("tppId", record.tppId())
                .addValue("key", record.idempotencyKey())
                .addValue("requestHash", record.requestHash())
                .addValue("fileId", record.fileId())
                .addValue("status", record.status().name())
                .addValue("now", Timestamp.from(now)));
        return rows == 1;
    }
}
