package com.enterprise.openfinance.bulkpayments.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The ON CONFLICT behaviour itself is proven against PostgreSQL in open-finance-bootstrap. */
class JdbcDpopJtiReplayStoreTest {

    private static final Instant EXPIRES = Instant.parse("2026-10-08T10:05:00Z");
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final JdbcDpopJtiReplayStore store = new JdbcDpopJtiReplayStore(jdbc);

    @Test
    void storesTheHashedJtiAndReportsFirstUseOnly() {
        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        when(jdbc.update(eq(JdbcDpopJtiReplayStore.INSERT), params.capture())).thenReturn(1, 0);

        assertThat(store.registerIfAbsent("jti-1", EXPIRES)).isTrue();
        assertThat(store.registerIfAbsent("jti-1", EXPIRES)).isFalse();

        assertThat(params.getValue().getValue("jtiHash")).asString().hasSize(64).doesNotContain("jti-1");
        assertThat(params.getValue().getValue("expiresAt")).isEqualTo(Timestamp.from(EXPIRES));
    }

    @Test
    void purgesExpiredRows() {
        when(jdbc.update(anyString(), org.mockito.ArgumentMatchers.any(MapSqlParameterSource.class))).thenReturn(3);

        assertThat(store.purgeExpired(EXPIRES)).isEqualTo(3);
        verify(jdbc).update(eq(JdbcDpopJtiReplayStore.PURGE_EXPIRED), org.mockito.ArgumentMatchers.any(MapSqlParameterSource.class));
    }
}
