package dev.cauce.governance.audit.signing;

/** Why a present signature could not be checked by this instance. */
public enum UnverifiableReason {

    /**
     * No public key is published for the {@code key_id} the entry names. The registry is
     * incomplete, or this instance reads a different one — never a statement about the entry.
     */
    MISSING_PUBLIC_KEY,

    /** The entry names a signature scheme this build does not implement. */
    UNKNOWN_SCHEME
}
