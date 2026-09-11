package dev.cauce.governance.audit.signing;

import java.security.GeneralSecurityException;
import java.security.Signature;
import java.util.Base64;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Checks one entry's signature against the public key its {@code key_id} names.
 *
 * <p>Always present, unlike {@link AuditEntrySigner}: an instance that cannot sign can still
 * be asked to verify, and must answer honestly about what it could and could not check. The
 * three outcomes are deliberately distinct — {@link AuditSignatureCheck} carries no boolean,
 * because "not checked" is not "checked and failed".
 */
@Component
public class AuditSignatureVerifier {

    private static final String ALGORITHM = "Ed25519";

    private final AuditKeyRegistry registry;

    public AuditSignatureVerifier(AuditKeyRegistry registry) {
        this.registry = registry;
    }

    /**
     * Checks {@code signature} over {@code entryHash}.
     *
     * @param signature base64 signature as stored, or null for an unsigned entry
     */
    public AuditSignatureCheck check(String signature, String keyId, String signatureScheme,
                                     String entryHash) {
        if (signature == null && keyId == null && signatureScheme == null) {
            return AuditSignatureCheck.unsigned();
        }
        if (signature == null || keyId == null || signatureScheme == null) {
            // Half a signature is not a signature: something wrote an inconsistent row.
            return AuditSignatureCheck.invalid(keyId);
        }
        if (!AuditEntrySigner.SIGNATURE_SCHEME.equals(signatureScheme)) {
            // A scheme this build does not implement. Not a failure — an answer it cannot give.
            return AuditSignatureCheck.unverifiable(keyId, UnverifiableReason.UNKNOWN_SCHEME);
        }
        Optional<AuditSigningKey> key = registry.find(keyId);
        if (key.isEmpty()) {
            return AuditSignatureCheck.unverifiable(keyId, UnverifiableReason.MISSING_PUBLIC_KEY);
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(signature);
        } catch (IllegalArgumentException notBase64) {
            return AuditSignatureCheck.invalid(keyId);
        }
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(key.get().publicKey());
            verifier.update(AuditSignaturePreimage.of(keyId, signatureScheme, entryHash));
            return verifier.verify(decoded)
                    ? AuditSignatureCheck.verified(key.get())
                    : AuditSignatureCheck.invalid(keyId);
        } catch (GeneralSecurityException e) {
            return AuditSignatureCheck.invalid(keyId);
        }
    }
}
