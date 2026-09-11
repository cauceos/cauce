package dev.cauce.governance.audit.signing;

import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the signing layer from {@link AuditSigningProperties}. Both halves are optional and
 * independent, and their absence is a supported configuration, not a degraded one: the
 * instance starts, writes, and verifies what it honestly can.
 */
@Configuration
@EnableConfigurationProperties(AuditSigningProperties.class)
public class AuditSigningConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AuditSigningConfiguration.class);

    private static final String ALGORITHM = "Ed25519";

    /** Probe signed at startup to prove the configured key pair actually belongs together. */
    private static final String SELF_TEST_PROBE = "cauce-audit-signing-self-test";

    /**
     * The signer, or {@code null} when no private key is configured — injected through an
     * {@code ObjectProvider}, so "do not sign" needs no second code path at the call site.
     */
    @Bean
    public AuditEntrySigner auditEntrySigner(AuditSigningProperties properties) {
        String encodedPrivate = properties.getPrivateKey();
        if (encodedPrivate == null || encodedPrivate.isBlank()) {
            log.info("Audit signing is disabled: no cauce.security.audit-signing.private-key is "
                    + "configured. Entries will be written unsigned.");
            return null;
        }
        String encodedPublic = properties.getPublicKey();
        if (encodedPublic == null || encodedPublic.isBlank()) {
            throw new IllegalStateException("cauce.security.audit-signing.private-key is set but "
                    + "public-key is not. Both are required: the key_id stamped on every entry "
                    + "is derived from the PUBLIC key, and the pair is self-tested at startup.");
        }
        PrivateKey privateKey = readPrivateKey(encodedPrivate);
        PublicKey publicKey = readPublicKey(encodedPublic);
        String keyId = AuditKeyId.of(publicKey);
        AuditEntrySigner signer = new AuditEntrySigner(privateKey, keyId);
        selfTest(signer, publicKey, keyId);
        log.info("Audit signing enabled with key_id {}", keyId);
        return signer;
    }

    /** The published registry, or an empty one when no path is configured. */
    @Bean
    public AuditKeyRegistry auditKeyRegistry(AuditSigningProperties properties) {
        String path = properties.getRegistryPath();
        if (path == null || path.isBlank()) {
            log.info("No audit key registry configured: signatures cannot be checked, and "
                    + "verification will report them as unverifiable rather than as broken.");
            return AuditKeyRegistry.empty();
        }
        AuditKeyRegistry registry = AuditKeyRegistry.load(Path.of(path));
        log.info("Audit key registry loaded from {} ({} keys)", path, registry.size());
        return registry;
    }

    /**
     * Signs a probe and verifies it with the configured public key. The JDK offers no way to
     * derive an Ed25519 public key from its private half, so the pair is configured as two
     * values — and a mismatched pair would otherwise produce entries that are signed, stamped
     * with a key_id that cannot verify them, and only discovered at audit time. This turns
     * that into a startup failure.
     */
    private static void selfTest(AuditEntrySigner signer, PublicKey publicKey, String keyId) {
        AuditKeyRegistry probeRegistry = AuditKeyRegistry.of(
                new AuditSigningKey(keyId, publicKey, null, null, null));
        AuditSignatureCheck check = new AuditSignatureVerifier(probeRegistry).check(
                signer.sign(SELF_TEST_PROBE), keyId, signer.signatureScheme(), SELF_TEST_PROBE);
        if (check.outcome() != AuditSignatureCheck.Outcome.VERIFIED) {
            throw new IllegalStateException("the configured audit signing keys do not form a "
                    + "pair: signatures made with the private key do not verify against the "
                    + "configured public key (key_id " + keyId + ")");
        }
    }

    private static PrivateKey readPrivateKey(String base64Pkcs8) {
        try {
            byte[] der = Base64.getDecoder().decode(base64Pkcs8.trim());
            return KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IllegalArgumentException | NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("cauce.security.audit-signing.private-key is not a "
                    + "base64 PKCS#8 Ed25519 private key", e);
        }
    }

    private static PublicKey readPublicKey(String base64Spki) {
        try {
            byte[] der = Base64.getDecoder().decode(base64Spki.trim());
            return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(der));
        } catch (IllegalArgumentException | NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("cauce.security.audit-signing.public-key is not a "
                    + "base64 SubjectPublicKeyInfo Ed25519 public key", e);
        }
    }
}
