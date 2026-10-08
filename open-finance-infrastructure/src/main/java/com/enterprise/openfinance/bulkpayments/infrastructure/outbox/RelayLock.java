package com.enterprise.openfinance.bulkpayments.infrastructure.outbox;

import java.util.Optional;

/**
 * Lets one replica relay at a time, for a whole run (claim, sends and
 * outcomes), without a database transaction open across the Kafka sends.
 */
public interface RelayLock {

    /** @return the held lock, or empty when another replica holds it */
    Optional<Held> tryAcquire();

    /** A held lock; closing releases it. */
    interface Held extends AutoCloseable {
        @Override
        void close();
    }
}
