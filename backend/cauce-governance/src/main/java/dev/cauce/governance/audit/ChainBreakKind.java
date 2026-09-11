package dev.cauce.governance.audit;

/**
 * The first defect the {@link AuditChainVerifier} found in a tenant's chain — each kind
 * points at what an attacker (or a bug) touched.
 */
public enum ChainBreakKind {
    /** The stored payload no longer hashes to the persisted {@code payload_hash}. */
    PAYLOAD_HASH_MISMATCH,
    /** The entry's stored fields no longer hash to the persisted {@code entry_hash}. */
    ENTRY_HASH_MISMATCH,
    /** The link is broken: {@code prev_hash} is not the previous entry's {@code entry_hash}. */
    PREV_HASH_MISMATCH,
    /** The per-tenant sequence is not contiguous from 1 — a row is missing. */
    SEQUENCE_GAP,
    /** An unhashed (pre-chain) row appears after chained rows — only legal as a prefix. */
    PRE_CHAIN_AFTER_CHAINED,

    /**
     * An entry carries a signature that does NOT verify against the public key its
     * {@code key_id} names. Distinct from a signature that could not be checked at all, which
     * is not a break and is counted in the {@link SignatureReport} instead.
     */
    SIGNATURE_MISMATCH,
    /** A chained row misses one of its chain fields or names an unknown hash scheme. */
    MALFORMED_ENTRY
}
