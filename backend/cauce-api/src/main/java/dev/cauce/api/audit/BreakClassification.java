package dev.cauce.api.audit;

import dev.cauce.governance.audit.ChainBreakKind;

/**
 * Public classification of the first break found in a tenant's audit chain. Cauce's own
 * vocabulary, mapped from the internal {@link ChainBreakKind} so internal names stay
 * decoupled from the wire contract — the same principle as
 * {@link dev.cauce.api.invocation.FailureReason}.
 *
 * <p>The internal taxonomy has six kinds; the two hash mismatches (payload and entry) both
 * mean "a stored entry no longer matches the hash recorded when it was chained" and carry no
 * actionable distinction for an external reader, so they collapse into {@link #ENTRY_ALTERED}.
 */
public enum BreakClassification {

    /** A stored entry's content or metadata no longer matches the hash recorded when chained. */
    ENTRY_ALTERED,
    /** An entry does not link to the previous entry's hash: the chain was cut or reordered. */
    LINK_BROKEN,
    /** The per-tenant sequence skips a number: a chained entry is no longer present. */
    ENTRY_MISSING,
    /** An entry without chain hashes appears after chained entries (valid only as a prefix). */
    UNCHAINED_ENTRY_OUT_OF_ORDER,
    /** A chained entry is missing a required hash field or names an unrecognized hash scheme. */
    ENTRY_MALFORMED;

    /** Maps the internal break taxonomy to the public vocabulary (exhaustive, directional). */
    public static BreakClassification from(ChainBreakKind kind) {
        return switch (kind) {
            case PAYLOAD_HASH_MISMATCH, ENTRY_HASH_MISMATCH -> ENTRY_ALTERED;
            case PREV_HASH_MISMATCH -> LINK_BROKEN;
            case SEQUENCE_GAP -> ENTRY_MISSING;
            case PRE_CHAIN_AFTER_CHAINED -> UNCHAINED_ENTRY_OUT_OF_ORDER;
            case MALFORMED_ENTRY -> ENTRY_MALFORMED;
        };
    }
}
