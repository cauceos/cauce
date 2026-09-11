package dev.cauce.api.audit;

import dev.cauce.governance.audit.ChainVerdict;

/**
 * Public outcome of verifying a tenant's audit chain — four states, mapped one to one from the
 * internal {@link ChainVerdict}. What each does and does not cover is spelled out in
 * {@link VerificationScope} on every response.
 *
 * <p>Precedence when more than one applies: BROKEN over TRUNCATED over UNVERIFIABLE over
 * VALID.
 */
public enum ChainStatus {

    /**
     * Recomputation and every checkable signature consistent from genesis to head — and, when
     * an {@code expected_head} was supplied, the chain reaches it with that hash.
     */
    VALID,

    /** An inconsistency was found; {@code first_break} says where and of what kind. */
    BROKEN,

    /**
     * The chain is consistent but ends before the {@code expected_head} the caller supplied.
     * The signature of a restored backup, not of an alteration. Only ever issued against a
     * caller-supplied head: from inside the database a truncation cannot be told from a chain
     * that was never longer, and this status is never guessed.
     */
    TRUNCATED,

    /**
     * No verdict can be issued for part of the chain: a signed entry names a key whose public
     * key is not published to this instance, or an entry uses a scheme this build does not
     * implement. Neither good nor bad — no answer. {@code signatures} and
     * {@code unverifiable_from_sequence} say which part.
     */
    UNVERIFIABLE;

    /** One to one; the internal and public vocabularies coincide here on purpose. */
    public static ChainStatus from(ChainVerdict verdict) {
        return switch (verdict) {
            case VALID -> VALID;
            case BROKEN -> BROKEN;
            case TRUNCATED -> TRUNCATED;
            case UNVERIFIABLE -> UNVERIFIABLE;
        };
    }
}
