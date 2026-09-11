package dev.cauce.governance.audit.signing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Signing configuration, bound from {@code cauce.security.audit-signing}.
 *
 * <p>Same mechanism as the API-key pepper ({@code cauce.security.api-key-pepper}): process
 * configuration, from the environment in production, never in the database and never in
 * version control. With one deliberate difference — <b>there is no dev default</b>. A
 * non-secret default pepper only makes dev API keys verifiable; a non-secret default signing
 * key would make dev signatures FORGEABLE and would put a private key in the repository.
 *
 * <p>Both properties are independent and both are optional:
 * <ul>
 *   <li>no {@code private-key} → entries are written unsigned, and the instance runs
 *       normally. Signing is additive, never a startup requirement;</li>
 *   <li>no {@code registry-path} → no signature can be checked, which verification reports as
 *       unverifiable coverage rather than as a broken chain.</li>
 * </ul>
 * An instance can legitimately have one without the other: a signing instance whose operator
 * publishes the registry elsewhere, or a verifying instance that never writes.
 */
@ConfigurationProperties(prefix = "cauce.security.audit-signing")
public class AuditSigningProperties {

    /** Base64 PKCS#8 DER of the Ed25519 private key. Empty = do not sign. */
    private String privateKey = "";

    /**
     * Base64 SubjectPublicKeyInfo DER of the matching public key. Required whenever
     * {@code privateKey} is set: the JDK cannot derive an Ed25519 public key from its private
     * half, the {@code key_id} stamped on every entry is derived from the public key, and the
     * pair is self-tested at startup so a mismatch fails there instead of at audit time.
     */
    private String publicKey = "";

    /** Filesystem path of the published key registry JSON. Empty = cannot verify signatures. */
    private String registryPath = "";

    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    public String getRegistryPath() {
        return registryPath;
    }

    public void setRegistryPath(String registryPath) {
        this.registryPath = registryPath;
    }
}
