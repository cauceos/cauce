package dev.cauce.governance.audit;

/**
 * The outcomes of verifying a tenant's audit chain (ADR 0003 §4): an alteration was found, or
 * nothing was found, or this instance could not look at all of it. Distinguishing the last
 * from the first is what keeps a chain merely unreadable here from being called broken.
 *
 * <p>Precedence when more than one applies: {@link #BROKEN} over {@link #UNVERIFIABLE} over
 * {@link #VALID}. A found inconsistency is never softened by a missing key.
 *
 * <p>Deliberately absent: a "truncated" verdict. From inside the database a chain shortened
 * to a consistent earlier state — a restored backup — is indistinguishable from one that was
 * never longer, because a restore is a consistent snapshot and every internal reference moves
 * with it. Issuing that verdict needs a reference held outside the database, which is what
 * external anchoring (deferred in ADR 0003) will supply. Until then it is not guessed.
 */
public enum ChainVerdict {

    /** Recomputation and every checkable signature consistent from genesis to head. */
    VALID,

    /** An inconsistency was found. The result carries the exact first break and its kind. */
    BROKEN,

    /**
     * No verdict can be issued for part of the chain: a signed entry names a key id whose
     * public key is not published here, or an entry carries a hash or signature scheme this
     * build does not implement. Neither good nor bad — <b>no answer</b>. It exists so that a
     * chain merely unreadable by this verifier is never called broken.
     */
    UNVERIFIABLE
}
