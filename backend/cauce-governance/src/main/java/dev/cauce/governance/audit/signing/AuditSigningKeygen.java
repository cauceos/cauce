package dev.cauce.governance.audit.signing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

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
 *
 * <p>With {@code --into <dir>} it writes the pair and a one-key registry into that directory
 * instead ({@link #writeInto}), for a local instance that signs from its first start with no
 * JDK on the host: the quickstart runs it inside the application image before the app.
 */
public final class AuditSigningKeygen {

    /** Base64 PKCS#8 private key, the value of {@code cauce.security.audit-signing.private-key}. */
    public static final String PRIVATE_KEY_FILE = "private-key";
    /** Base64 SubjectPublicKeyInfo public key, the value of {@code ...audit-signing.public-key}. */
    public static final String PUBLIC_KEY_FILE = "public-key";
    /** The published registry (specification §8.4) holding the one public key. */
    public static final String REGISTRY_FILE = "registry.json";

    private static final List<String> FILES = List.of(PRIVATE_KEY_FILE, PUBLIC_KEY_FILE, REGISTRY_FILE);

    private AuditSigningKeygen() {
    }

    public static void main(String[] args) {
        if (args.length == 2 && args[0].equals("--into")) {
            boolean created = writeInto(Path.of(args[1]));
            System.out.println(created
                    ? "Audit signing key generated into " + args[1]
                    : "Audit signing key already present in " + args[1] + "; left unchanged");
            return;
        }
        if (args.length != 0) {
            System.err.println("usage: AuditSigningKeygen [--into <dir>]");
            System.exit(2);
        }
        KeyPair pair = newPair();
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
        System.out.println(registryEntry(keyId, publicKey, Instant.now()).indent(3).stripTrailing());
        System.out.println();
        System.out.println("Rotating? Add this entry to the registry, never replace the old one:");
        System.out.println("existing entries must stay verifiable against the key that signed");
        System.out.println("them. Set \"retired_at\" on the previous key instead.");
        System.out.println();
    }

    /**
     * Writes a new key pair and its one-key registry into {@code directory}, unless a pair is
     * already there: a key is generated once and then kept, because replacing it would leave
     * everything it signed unverifiable. The private key file is readable by its owner only
     * where the file system supports POSIX permissions.
     *
     * @return {@code true} if the files were created, {@code false} if they were all present
     * @throws IllegalStateException if only some of the files are present — a half-written key
     *         is never completed or overwritten, it is for a person to look at
     */
    public static boolean writeInto(Path directory) {
        long present = FILES.stream().filter(name -> Files.exists(directory.resolve(name))).count();
        if (present == FILES.size()) {
            return false;
        }
        if (present > 0) {
            throw new IllegalStateException("audit signing key directory " + directory
                    + " holds only some of " + FILES + "; refusing to overwrite or complete it");
        }
        KeyPair pair = newPair();
        Base64.Encoder base64 = Base64.getEncoder();
        String publicKey = base64.encodeToString(pair.getPublic().getEncoded());
        String keyId = AuditKeyId.of(pair.getPublic());
        String registry = "{ \"registry_version\": 1, \"keys\": [\n"
                + registryEntry(keyId, publicKey, Instant.now()).indent(2) + "] }\n";
        try {
            Files.createDirectories(directory);
            Path privateKeyFile = directory.resolve(PRIVATE_KEY_FILE);
            Files.writeString(privateKeyFile, base64.encodeToString(pair.getPrivate().getEncoded()),
                    StandardCharsets.US_ASCII);
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(privateKeyFile, PosixFilePermissions.fromString("rw-------"));
            }
            Files.writeString(directory.resolve(PUBLIC_KEY_FILE), publicKey, StandardCharsets.US_ASCII);
            Files.writeString(directory.resolve(REGISTRY_FILE), registry, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write the audit signing key into " + directory, e);
        }
        return true;
    }

    private static KeyPair newPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("the JDK provides no Ed25519", e);
        }
    }

    private static String registryEntry(String keyId, String publicKey, Instant activatedAt) {
        return """
                {
                  "key_id": "%s",
                  "algorithm": "ed25519",
                  "public_key": "%s",
                  "activated_at": "%s",
                  "retired_at": null,
                  "compromised_at": null
                }""".formatted(keyId, publicKey, activatedAt);
    }
}
