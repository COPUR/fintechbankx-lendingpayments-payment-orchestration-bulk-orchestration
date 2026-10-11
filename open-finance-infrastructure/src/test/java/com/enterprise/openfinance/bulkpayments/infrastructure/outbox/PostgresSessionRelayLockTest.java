package com.enterprise.openfinance.bulkpayments.infrastructure.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The relay lock's session handling; the Postgres behaviour itself is in BulkOrchestrationServiceIT. */
class PostgresSessionRelayLockTest {

    private static final long KEY = OutboxRelay.RELAY_LOCK_KEY;
    private static final String TRY = "select pg_try_advisory_lock(?)";
    private static final String UNLOCK = "select pg_advisory_unlock(?)";

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement tryLock = mock(PreparedStatement.class);
    private final PreparedStatement unlock = mock(PreparedStatement.class);
    private final PostgresSessionRelayLock lock = new PostgresSessionRelayLock(dataSource, KEY);

    PostgresSessionRelayLockTest() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(TRY)).thenReturn(tryLock);
        when(connection.prepareStatement(UNLOCK)).thenReturn(unlock);
    }

    @Test
    void holdsTheSessionUntilClosedThenUnlocksBeforeReturningTheConnection() throws SQLException {
        answers(tryLock, true);
        answers(unlock, true);

        Optional<RelayLock.Held> held = lock.tryAcquire();

        assertThat(held).isPresent();
        verify(tryLock).setLong(1, KEY);
        verify(connection, never()).close();
        held.get().close();
        var order = inOrder(unlock, connection);
        order.verify(unlock).setLong(1, KEY);
        order.verify(unlock).executeQuery();
        order.verify(connection).close();
    }

    @Test
    void anotherSessionHoldingItReturnsTheConnectionAtOnce() throws SQLException {
        answers(tryLock, false);

        assertThat(lock.tryAcquire()).isEmpty();

        verify(connection).close();
    }

    @Test
    void neverRunsInsideATransaction() throws SQLException {
        when(connection.getAutoCommit()).thenReturn(false);
        answers(tryLock, false);

        lock.tryAcquire();

        verify(connection).setAutoCommit(true);
    }

    @Test
    void aSessionThatCannotUnlockIsDiscardedNotPooled() throws SQLException {
        answers(tryLock, true);
        when(unlock.executeQuery()).thenThrow(new SQLException("connection reset"));

        lock.tryAcquire().orElseThrow().close();

        verify(connection).abort(any(Executor.class));
        verify(connection).close();
    }

    @Test
    void databaseFailuresSurfaceAndReturnTheConnection() throws SQLException {
        when(tryLock.executeQuery()).thenThrow(new SQLException("terminating connection"));

        assertThatThrownBy(lock::tryAcquire).isInstanceOf(DataAccessResourceFailureException.class);
        verify(connection).close();

        when(dataSource.getConnection()).thenThrow(new SQLException("pool exhausted"));
        assertThatThrownBy(lock::tryAcquire).isInstanceOf(DataAccessResourceFailureException.class);
    }

    private static void answers(PreparedStatement statement, boolean value) throws SQLException {
        ResultSet result = mock(ResultSet.class);
        when(result.next()).thenReturn(true);
        when(result.getBoolean(1)).thenReturn(value);
        when(statement.executeQuery()).thenReturn(result);
    }
}
