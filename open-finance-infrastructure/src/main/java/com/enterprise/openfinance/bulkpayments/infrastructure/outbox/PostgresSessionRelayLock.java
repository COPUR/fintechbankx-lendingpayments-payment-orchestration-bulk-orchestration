package com.enterprise.openfinance.bulkpayments.infrastructure.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Session-level Postgres advisory lock (pg_try_advisory_lock) on a connection
 * of its own, in autocommit mode, so no transaction stays open while the
 * relay waits on Kafka. The connection is held for the run and the lock is
 * released before it goes back to the pool. If the session dies, Postgres
 * drops the lock with it and another replica may take over; consumers
 * de-duplicate on eventId.
 */
public final class PostgresSessionRelayLock implements RelayLock {

    private static final Logger log = LoggerFactory.getLogger(PostgresSessionRelayLock.class);

    private final DataSource dataSource;
    private final long key;

    public PostgresSessionRelayLock(DataSource dataSource, long key) {
        this.dataSource = dataSource;
        this.key = key;
    }

    @Override
    public Optional<Held> tryAcquire() {
        Connection connection;
        try {
            connection = dataSource.getConnection();
        } catch (SQLException e) {
            throw new DataAccessResourceFailureException("No connection for the outbox relay lock", e);
        }
        boolean held = false;
        try {
            if (!connection.getAutoCommit()) {
                connection.setAutoCommit(true);
            }
            held = call(connection, "select pg_try_advisory_lock(?)");
            return held ? Optional.of(() -> release(connection)) : Optional.empty();
        } catch (SQLException e) {
            throw new DataAccessResourceFailureException("Could not try the outbox relay lock", e);
        } finally {
            if (!held) {
                closeQuietly(connection);
            }
        }
    }

    private void release(Connection connection) {
        try {
            call(connection, "select pg_advisory_unlock(?)");
            closeQuietly(connection);
        } catch (SQLException e) {
            // Never hand a pooled session back still holding the lock: drop it, which also releases the lock.
            log.warn("Could not release the outbox relay lock ({}); discarding its connection", e.getClass().getSimpleName());
            try {
                connection.abort(Runnable::run);
            } catch (SQLException | RuntimeException ignored) {
                // the session is gone either way
            }
            closeQuietly(connection);
        }
    }

    private boolean call(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // nothing left to release
        }
    }
}
