package dev.cauce.governance.audit.signing;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Objects;

/**
 * Signs ledger entries with this instance's Ed25519 key. Present as a bean only when a signing
 * key is configured — an instance without one keeps running and writes unsigned entries.
 *
 * <p>Ed25519 is deterministic: it needs no per-signature nonce, so the failure mode where a
 * repeated or predictable nonce leaks the private key does not exist. It is in the JDK since
 * 15 (no new dependency) and in the standard library of every language an independent verifier
 * is likely to be written in.
 *
 * <p>Only v2 entries are signed. v1 entries predate this capability, and signing them now
 * would be signing history after the fact — the check is defensive: today's drainer writes
 * nothing but v2.
 *
 * <p>The private key comes from process configuration and is NEVER written to the database.
 * That is the entire security property: an actor holding database access can rewrite rows and
 * recompute hashes, but cannot produce a signature. An actor holding deployment access holds
 * the key too, and is explicitly outside what this defends against.
 */
public class AuditEntrySigner {

    /** Algorithm plus signature-preimage version, persisted per row. */
    public static final String SIGNATURE_SCHEME = "ed25519-v1";

    private static final String ALGORITHM = "Ed25519";

    private final PrivateKey privateKey;
    private final String keyId;

    public AuditEntrySigner(PrivateKey privateKey, String keyId) {
        this.privateKey = Objects.requireNonNull(privateKey, "privateKey must not be null");
        this.keyId = Objects.requireNonNull(keyId, "keyId must not be null");
    }

    /** The id of the key this instance signs with; stamped on every entry it signs. */
    public String keyId() {
        return keyId;
    }

    /** The scheme this signer produces. */
    public String signatureScheme() {
        return SIGNATURE_SCHEME;
    }

    /**
     * Signs {@code entryHash}, returning the base64 signature.
     *
     * @throws IllegalStateException if signing fails — a configured key that cannot sign is a
     *     misconfiguration, and the caller must fail rather than quietly store the entry
     *     unsigned, which would be a silent downgrade
     */
    public String sign(String entryHash) {
        Objects.requireNonNull(entryHash, "entryHash must not be null");
        byte[] preimage = AuditSignaturePreimage.of(keyId, SIGNATURE_SCHEME, entryHash);
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initSign(privateKey);
            signature.update(preimage);
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("audit entry signing failed", e);
        }
    }
}
