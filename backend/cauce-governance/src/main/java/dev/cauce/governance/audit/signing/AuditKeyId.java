package dev.cauce.governance.audit.signing;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Derives a signing key's {@code key_id}: the first 8 bytes of SHA-256 over the public key's
 * SubjectPublicKeyInfo DER encoding, hex-encoded (16 characters).
 *
 * <p>The input is exactly what {@link PublicKey#getEncoded()} returns for an Ed25519 key —
 * the RFC 8410 SubjectPublicKeyInfo — which is also exactly the payload of a PEM
 * {@code BEGIN PUBLIC KEY} block. An independent implementation can therefore reproduce a
 * {@code key_id} from the published public key alone, without any Cauce-specific knowledge.
 *
 * <p>Truncating to 64 bits is deliberate and safe HERE: the {@code key_id} is a lookup
 * identifier, not a cryptographic commitment. Nothing is trusted because two ids match — the
 * signature itself is what is checked, and a collision would only cause the wrong public key
 * to be tried, which fails verification. It is a digest of the PUBLIC key, so publishing it
 * on every ledger row reveals nothing: it is not key material and cannot be reversed into it.
 */
public final class AuditKeyId {

    /** Length in hex characters of every derived id. */
    public static final int LENGTH = 16;

    private AuditKeyId() {
    }

    /** The {@code key_id} of {@code publicKey}. */
    public static String of(PublicKey publicKey) {
        Objects.requireNonNull(publicKey, "publicKey must not be null");
        byte[] encoded = publicKey.getEncoded();
        if (encoded == null) {
            throw new IllegalArgumentException("public key does not expose an encoded form");
        }
        return of(encoded);
    }

    /** The {@code key_id} of a public key already in SubjectPublicKeyInfo DER form. */
    public static String of(byte[] subjectPublicKeyInfo) {
        Objects.requireNonNull(subjectPublicKeyInfo, "subjectPublicKeyInfo must not be null");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(subjectPublicKeyInfo);
            return HexFormat.of().formatHex(Arrays.copyOf(digest, LENGTH / 2));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
        }
    }
}
