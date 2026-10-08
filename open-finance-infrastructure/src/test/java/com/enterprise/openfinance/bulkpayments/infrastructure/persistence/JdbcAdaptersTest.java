package com.enterprise.openfinance.bulkpayments.infrastructure.persistence;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkFileStatus;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkIdempotencyRecord;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkItemResult;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkItemStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SQL behaviour is proven against PostgreSQL in open-finance-bootstrap; these cover the adapter logic. */
class JdbcAdaptersTest {

    private static final Instant NOW = Instant.parse("2026-02-09T10:00:00Z");
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);

    @Test
    void writesItemsInBoundedChunks() {
        List<BulkItemResult> items = IntStream.rangeClosed(1, 2_500)
                .mapToObj(line -> BulkItemResult.accepted(line, "INS-" + line, "AE120001000000000000000001", BigDecimal.ONE))
                .toList();

        new JdbcBulkItemAdapter(jdbc).saveAll("FILE-1", items);

        ArgumentCaptor<SqlParameterSource[]> batches = ArgumentCaptor.forClass(SqlParameterSource[].class);
        verify(jdbc, times(3)).batchUpdate(anyString(), batches.capture());
        assertThat(batches.getAllValues()).extracting(batch -> batch.length).containsExactly(1_000, 1_000, 500);
        assertThat(batches.getAllValues().get(2)[499].getValue("lineNumber")).isEqualTo(2_500);
        assertThat(batches.getAllValues().get(0)[0].getValue("status")).isEqualTo("ACCEPTED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsItemRowsAndBoundsTheUnprocessedQuery() throws Exception {
        JdbcBulkItemAdapter adapter = new JdbcBulkItemAdapter(jdbc);
        ArgumentCaptor<RowMapper<BulkItemResult>> mapper = ArgumentCaptor.forClass(RowMapper.class);
        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        when(jdbc.query(anyString(), params.capture(), mapper.capture())).thenReturn(List.of());

        adapter.findUnprocessed("FILE-1", 50);
        adapter.findByFileId("FILE-1");

        assertThat(params.getAllValues().get(0).getValue("limit")).isEqualTo(50);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getInt("line_number")).thenReturn(7);
        when(rs.getString("instruction_id")).thenReturn("INS-7");
        when(rs.getString("payee_iban")).thenReturn("AE000");
        when(rs.getBigDecimal("amount")).thenReturn(new BigDecimal("12.5000"));
        when(rs.getString("status")).thenReturn("REJECTED");
        when(rs.getString("error_message")).thenReturn("Invalid IBAN");
        BulkItemResult item = mapper.getValue().mapRow(rs, 0);
        assertThat(item.lineNumber()).isEqualTo(7);
        assertThat(item.status()).isEqualTo(BulkItemStatus.REJECTED);
        assertThat(item.amount()).isEqualByComparingTo("12.5");
        assertThat(item.errorMessage()).isEqualTo("Invalid IBAN");
    }

    @Test
    void marksProcessedLinesAndSkipsEmptyBatches() {
        JdbcBulkItemAdapter adapter = new JdbcBulkItemAdapter(jdbc);
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(2);

        assertThat(adapter.markProcessed("FILE-1", List.of(1, 2), NOW)).isEqualTo(2);
        assertThat(adapter.markProcessed("FILE-1", List.of(), NOW)).isZero();
        verify(jdbc, times(1)).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void reserveReportsWhetherThisCallOwnsTheKey() {
        JdbcBulkIdempotencyAdapter adapter = new JdbcBulkIdempotencyAdapter(jdbc);
        BulkIdempotencyRecord record = new BulkIdempotencyRecord("IDEMP-1", "TPP-001", "hash", "FILE-1",
                BulkFileStatus.PROCESSING, NOW.plusSeconds(60));
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1).thenReturn(0);

        assertThat(adapter.reserve(record, NOW)).isTrue();
        assertThat(adapter.reserve(record, NOW)).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void findMapsTheIdempotencyRow() throws Exception {
        JdbcBulkIdempotencyAdapter adapter = new JdbcBulkIdempotencyAdapter(jdbc);
        ArgumentCaptor<RowMapper<BulkIdempotencyRecord>> mapper = ArgumentCaptor.forClass(RowMapper.class);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), mapper.capture())).thenReturn(List.of());

        assertThat(adapter.find("IDEMP-1", "TPP-001", NOW)).isEmpty();

        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("idempotency_key")).thenReturn("IDEMP-1");
        when(rs.getString("tpp_id")).thenReturn("TPP-001");
        when(rs.getString("request_hash")).thenReturn("hash");
        when(rs.getString("file_id")).thenReturn("FILE-1");
        when(rs.getString("file_status")).thenReturn("PROCESSING");
        when(rs.getTimestamp("expires_at")).thenReturn(Timestamp.from(NOW));
        BulkIdempotencyRecord record = mapper.getValue().mapRow(rs, 0);
        assertThat(record.fileId()).isEqualTo("FILE-1");
        assertThat(record.expiresAt()).isEqualTo(NOW);
        verify(jdbc, never()).update(anyString(), eq(new MapSqlParameterSource()));
    }
}
