package dev.cauce.governance.audit.signing;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The bytes actually signed for one ledger entry:
 * {@code cauce-audit-signature:v1:<key_id>:<signature_scheme>:<entry_hash>}, UTF-8.
 *
 * <p>The signature covers the entry's chained {@code entry_hash} rather than a fresh
 * serialisation of its fields. That hash already commits to the previous entry's hash, so
 * signing it commits to the whole prefix of the chain, and the signing layer needs no opinion
 * about how entries are hashed.
 *
 * <p>Three things are bound into the preimage rather than sitting next to it:
 * <ul>
 *   <li>the <b>domain separation prefix</b>, so a signature produced here can never be valid
 *       in another context signed by the same key — the export artefact, for one, which will
 *       use its own prefix;</li>
 *   <li>the <b>key_id</b>, so a signature cannot be moved to a row claiming a different key;
 *   </li>
 *   <li>the <b>signature scheme</b>, so a future scheme cannot have its signatures replayed
 *       as if they were this one's.</li>
 * </ul>
 *
 * <p>The prefix carries its own {@code v1} version, independent of both the hash scheme and
 * the signature scheme: it names the shape of THIS string, and changing that shape is a
 * different kind of change from swapping the algorithm.
 */
public final class AuditSignaturePreimage {

    /** Domain separation. Frozen: changing it invalidates every signature ever made. */
    private static final String PREFIX = "cauce-audit-signature:v1:";

    private AuditSignaturePreimage() {
    }

    /** The UTF-8 bytes to sign or verify for one entry. */
    public static byte[] of(String keyId, String signatureScheme, String entryHash) {
        Objects.requireNonNull(keyId, "keyId must not be null");
        Objects.requireNonNull(signatureScheme, "signatureScheme must not be null");
        Objects.requireNonNull(entryHash, "entryHash must not be null");
        return (PREFIX + keyId + ":" + signatureScheme + ":" + entryHash)
                .getBytes(StandardCharsets.UTF_8);
    }
}
