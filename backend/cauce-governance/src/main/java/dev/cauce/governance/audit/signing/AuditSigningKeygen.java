package dev.cauce.governance.audit.signing;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Generates an Ed25519 signing key pair and prints everything needed to configure it: the
 * private key for the environment, and a ready-to-paste registry entry.
 *
 * <p>It exists because cold start would otherwise be an error-prone manual procedure. The
 * private and public keys can be produced with {@code openssl genpkey -algorithm ed25519},
 * but the {@code key_id} is a truncated digest over the DER encoding, and computing that by
 * hand is exactly where an operator gets it subtly wrong — producing a registry that names a
 * key it does not contain, discovered only when an audit fails.
 *
 * <p>Run it with:
 * <pre>{@code ./gradlew :cauce-governance:generateAuditSigningKey}</pre>
 *
 * <p>The private key is printed to stdout ONCE and never stored. Put it in the environment as
 * {@code AUDIT_SIGNING_PRIVATE_KEY}, put the registry entry in the published registry file,
 * and keep a backup of both — separately. Losing the private key stops new signing; losing the
 * public key makes everything it signed unverifiable, which is worse and irreversible.
 */
public final class AuditSigningKeygen {

    private AuditSigningKeygen() {
    }

    public static void main(String[] args) throws NoSuchAlgorithmException {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Base64.Encoder base64 = Base64.getEncoder();
        String privateKey = base64.encodeToString(pair.getPrivate().getEncoded());
        String publicKey = base64.encodeToString(pair.getPublic().getEncoded());
        String keyId = AuditKeyId.of(pair.getPublic());

        System.out.println();
        System.out.println("key_id: " + keyId);
        System.out.println();
        System.out.println("1. Environment of the signing instance (SECRET, shown once):");
        System.out.println();
        System.out.println("   AUDIT_SIGNING_PRIVATE_KEY=" + privateKey);
        System.out.println("   AUDIT_SIGNING_PUBLIC_KEY=" + publicKey);
        System.out.println();
        System.out.println("2. Entry for the published key registry (NOT secret):");
        System.out.println();
        System.out.println("   {");
        System.out.println("     \"key_id\": \"" + keyId + "\",");
        System.out.println("     \"algorithm\": \"ed25519\",");
        System.out.println("     \"public_key\": \"" + publicKey + "\",");
        System.out.println("     \"activated_at\": \"" + java.time.Instant.now() + "\",");
        System.out.println("     \"retired_at\": null,");
        System.out.println("     \"compromised_at\": null");
        System.out.println("   }");
        System.out.println();
        System.out.println("Rotating? Add this entry to the registry, never replace the old one:");
        System.out.println("existing entries must stay verifiable against the key that signed");
        System.out.println("them. Set \"retired_at\" on the previous key instead.");
        System.out.println();
    }
}
