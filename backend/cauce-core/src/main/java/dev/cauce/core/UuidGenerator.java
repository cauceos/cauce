package dev.cauce.core;

import com.github.f4b6a3.uuid.UuidCreator;
import java.util.UUID;

/**
 * Central factory for the identifiers used across the domain.
 *
 * <p>The whole system uses time-ordered UUIDv7 (RFC 9562) for primary keys. Unlike the
 * random UUIDv4, a v7 embeds a millisecond timestamp in its most significant bits, so
 * ids generated over time sort in creation order. That ordering gives much better
 * locality on B-tree primary-key indexes (near-sequential inserts instead of random
 * ones), reducing page splits and index bloat in PostgreSQL.
 *
 * <p>Centralising generation here keeps the choice of UUID scheme in one place and lets
 * other modules (and their tests) mint domain ids without depending on the underlying
 * uuid-creator library directly, which stays an {@code implementation} detail of
 * cauce-core.
 */
public final class UuidGenerator {

    private UuidGenerator() {
    }

    /** Returns a new time-ordered UUIDv7. */
    public static UUID newV7() {
        return UuidCreator.getTimeOrderedEpoch();
    }
}
