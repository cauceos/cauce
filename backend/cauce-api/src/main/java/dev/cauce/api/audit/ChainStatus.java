package dev.cauce.api.audit;

import dev.cauce.governance.audit.ChainVerdict;

/**
 * Public outcome of verifying a tenant's audit chain — three states, mapped one to one from
 * the internal {@link ChainVerdict}. What each does and does not cover is spelled out in
 * {@link VerificationScope} on every response.
 *
 * <p>Precedence when more than one applies: BROKEN over UNVERIFIABLE over VALID.
 *
 * <p>There is no "truncated" status. A chain shortened to a consistent earlier state cannot
 * be told, from inside the database, from one that was never longer; {@code head} on every
 * response is the value a reference outside the database would be built from.
 */
public enum ChainStatus {

    /** Recomputation and every checkable signature consistent from genesis to head. */
    VALID,

    /** An inconsistency was found; {@code first_break} says where and of what kind. */
    BROKEN,

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
            case UNVERIFIABLE -> UNVERIFIABLE;
        };
    }
}
