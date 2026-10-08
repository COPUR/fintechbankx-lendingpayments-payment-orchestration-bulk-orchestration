package com.enterprise.openfinance.bulkpayments.infrastructure.persistence;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkItemResult;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkItemStatus;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkItemPort;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Items of a bulk file in sc_pay_bulk_orchestration.bulk_item. Plain JDBC on
 * purpose: a file can hold hundreds of thousands of items, which are written
 * in JDBC batches of {@link #WRITE_CHUNK} rows and never loaded as entities.
 */
@Repository
public class JdbcBulkItemAdapter implements BulkItemPort {

    static final int WRITE_CHUNK = 1_000;

    private static final String COLUMNS =
            "line_number, instruction_id, payee_iban, amount, status, error_message";

    private static final RowMapper<BulkItemResult> ROW_MAPPER = (rs, rowNum) -> new BulkItemResult(
            rs.getInt("line_number"),
            rs.getString("instruction_id"),
            rs.getString("payee_iban"),
            rs.getBigDecimal("amount"),
            BulkItemStatus.valueOf(rs.getString("status")),
            rs.getString("error_message"));

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcBulkItemAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void saveAll(String fileId, List<BulkItemResult> items) {
        for (int from = 0; from < items.size(); from += WRITE_CHUNK) {
            List<BulkItemResult> chunk = items.subList(from, Math.min(from + WRITE_CHUNK, items.size()));
            SqlParameterSource[] rows = chunk.stream()
                    .map(item -> new MapSqlParameterSource()
                            .addValue("fileId", fileId)
                            .addValue("lineNumber", item.lineNumber())
                            .addValue("instructionId", item.instructionId())
                            .addValue("payeeIban", item.payeeIban())
                            .addValue("amount", item.amount())
                            .addValue("status", item.status().name())
                            .addValue("errorMessage", item.errorMessage()))
                    .toArray(SqlParameterSource[]::new);
            jdbc.batchUpdate("""
                    insert into bulk_item (file_id, line_number, instruction_id, payee_iban, amount, status, error_message)
                    values (:fileId, :lineNumber, :instructionId, :payeeIban, :amount, :status, :errorMessage)
                    """, rows);
        }
    }

    @Override
    public List<BulkItemResult> findByFileId(String fileId) {
        return jdbc.query("select " + COLUMNS + " from bulk_item where file_id = :fileId order by line_number",
                new MapSqlParameterSource("fileId", fileId), ROW_MAPPER);
    }

    @Override
    public List<BulkItemResult> findUnprocessed(String fileId, int limit) {
        return jdbc.query("select " + COLUMNS + " from bulk_item where file_id = :fileId and processed_at is null"
                        + " order by line_number limit :limit",
                new MapSqlParameterSource("fileId", fileId).addValue("limit", limit), ROW_MAPPER);
    }

    @Override
    public int markProcessed(String fileId, Collection<Integer> lineNumbers, Instant processedAt) {
        if (lineNumbers.isEmpty()) {
            return 0;
        }
        return jdbc.update("""
                update bulk_item set processed_at = :processedAt
                where file_id = :fileId and line_number in (:lines) and processed_at is null
                """, new MapSqlParameterSource("fileId", fileId)
                .addValue("lines", lineNumbers)
                .addValue("processedAt", Timestamp.from(processedAt)));
    }
}
