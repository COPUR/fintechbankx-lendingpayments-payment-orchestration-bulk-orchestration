package com.enterprise.openfinance.bulkpayments.infrastructure.security;

import java.time.Instant;

/** Remembers DPoP proof ids across replicas until they can no longer pass the iat window. */
@FunctionalInterface
public interface DpopJtiReplayStore {

    /** @return true when the jti was not seen before (first use), false for a replay */
    boolean registerIfAbsent(String jti, Instant expiresAt);
}
