package dev.cauce.governance.audit.signing;

/**
 * The outcome of checking one entry's signature. Four outcomes, no boolean — the distinction
 * this type exists to preserve is that <b>"I could not check it" is not "it failed"</b>.
 * Collapsing the two would be the easiest available lie: calling a chain broken when it is
 * merely unreadable by this verifier.
 *
 * @param outcome what happened
 * @param keyId the key the entry names, when it names one
 * @param reason why it could not be checked, for {@link Outcome#UNVERIFIABLE} only
 * @param compromisedKey true when the entry verified against a key the registry marks as
 *     compromised — reported, never treated as a failure
 */
public record AuditSignatureCheck(Outcome outcome,
                                  String keyId,
                                  UnverifiableReason reason,
                                  boolean compromisedKey) {

    public enum Outcome {
        /** The entry carries no signature. Legitimate: v1 entries, or an unsigned instance. */
        UNSIGNED,
        /** Checked against the named public key and correct. */
        VERIFIED,
        /** Checked and WRONG. The entry is not what was signed. */
        INVALID,
        /** Not checkable by this instance: no public key, or a scheme it does not implement. */
        UNVERIFIABLE
    }

    public static AuditSignatureCheck unsigned() {
        return new AuditSignatureCheck(Outcome.UNSIGNED, null, null, false);
    }

    public static AuditSignatureCheck verified(AuditSigningKey key) {
        return new AuditSignatureCheck(Outcome.VERIFIED, key.keyId(), null, key.isCompromised());
    }

    public static AuditSignatureCheck invalid(String keyId) {
        return new AuditSignatureCheck(Outcome.INVALID, keyId, null, false);
    }

    public static AuditSignatureCheck unverifiable(String keyId, UnverifiableReason reason) {
        return new AuditSignatureCheck(Outcome.UNVERIFIABLE, keyId, reason, false);
    }
}
