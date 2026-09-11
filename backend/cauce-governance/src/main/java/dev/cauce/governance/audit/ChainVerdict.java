package dev.cauce.governance.audit;

/**
 * The four outcomes of verifying a tenant's audit chain (ADR 0003 §4). Distinguishing them is
 * what separates an operational accident from an alteration, and both of those from an answer
 * this instance simply cannot give.
 *
 * <p>Precedence when more than one applies: {@link #BROKEN} over {@link #TRUNCATED} over
 * {@link #UNVERIFIABLE} over {@link #VALID}. A found inconsistency is never softened by a
 * missing key, and a chain that fails to reach the caller's anchor is reported as such even
 * when some of its signatures could not be checked.
 */
public enum ChainVerdict {

    /**
     * Recomputation and every checkable signature consistent from genesis to head, and — when
     * the caller supplied an anchor — the chain reaches it with the expected hash.
     */
    VALID,

    /** An inconsistency was found. The result carries the exact first break and its kind. */
    BROKEN,

    /**
     * The chain is consistent but ends BEFORE the head the caller says it observed earlier.
     * The signature of a restored backup, not of an alteration.
     *
     * <p>Only ever produced against a caller-supplied anchor. From inside the database a
     * truncation is undetectable by construction: a restore is a consistent snapshot, so the
     * head row, the outbox, the timestamps and the chain's own verification entries all move
     * with it. Without an external reference there is nothing to compare against, and this
     * verdict is deliberately never guessed.
     */
    TRUNCATED,

    /**
     * No verdict can be issued for part of the chain: a signed entry names a key id whose
     * public key is not published here, or an entry carries a hash or signature scheme this
     * build does not implement. Neither good nor bad — <b>no answer</b>. It exists so that a
     * chain merely unreadable by this verifier is never called broken.
     */
    UNVERIFIABLE
}
