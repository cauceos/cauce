package dev.cauce.governance.audit.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Loading the published key registry — the file that must NOT live in the database. */
class AuditKeyRegistryTest {

    @TempDir
    private Path directory;

    private KeyPair keyPair;
    private String keyId;
    private String publicKeyBase64;

    @BeforeEach
    void setUp() throws NoSuchAlgorithmException {
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        keyId = AuditKeyId.of(keyPair.getPublic());
        publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    private Path registryFile(String json) throws IOException {
        Path file = directory.resolve("audit-keys.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    private String entry(String id, String publicKey, String compromisedAt) {
        return """
                { "registry_version": 1, "keys": [
                  { "key_id": "%s", "algorithm": "ed25519", "public_key": "%s",
                    "activated_at": "2026-01-01T00:00:00Z", "retired_at": null,
                    "compromised_at": %s } ] }
                """.formatted(id, publicKey, compromisedAt);
    }

    @Test
    void load_validRegistry_resolvesTheKeyById() throws IOException {
        AuditKeyRegistry registry = AuditKeyRegistry.load(
                registryFile(entry(keyId, publicKeyBase64, "null")));

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.find(keyId)).hasValueSatisfying(key -> {
            assertThat(key.publicKey()).isEqualTo(keyPair.getPublic());
            assertThat(key.isCompromised()).isFalse();
        });
    }

    @Test
    void load_compromisedKey_isMarked() throws IOException {
        AuditKeyRegistry registry = AuditKeyRegistry.load(
                registryFile(entry(keyId, publicKeyBase64, "\"2026-06-01T00:00:00Z\"")));

        assertThat(registry.find(keyId)).hasValueSatisfying(
                key -> assertThat(key.isCompromised()).isTrue());
    }

    /**
     * A registry naming a key it does not actually contain is rejected at startup. Otherwise
     * the mistake surfaces as an unverifiable audit, months later, with no obvious cause.
     */
    @Test
    void load_keyIdThatDoesNotMatchItsPublicKey_isRejected() throws IOException {
        Path file = registryFile(entry("0123456789abcdef", publicKeyBase64, "null"));

        assertThatThrownBy(() -> AuditKeyRegistry.load(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match its public key");
    }

    @Test
    void load_duplicateKeyId_isRejected() throws IOException {
        Path file = registryFile("""
                { "registry_version": 1, "keys": [
                  { "key_id": "%s", "algorithm": "ed25519", "public_key": "%s",
                    "activated_at": null, "retired_at": null, "compromised_at": null },
                  { "key_id": "%s", "algorithm": "ed25519", "public_key": "%s",
                    "activated_at": null, "retired_at": null, "compromised_at": null } ] }
                """.formatted(keyId, publicKeyBase64, keyId, publicKeyBase64));

        assertThatThrownBy(() -> AuditKeyRegistry.load(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("twice");
    }

    @Test
    void load_unsupportedAlgorithm_isRejected() throws IOException {
        Path file = registryFile("""
                { "registry_version": 1, "keys": [
                  { "key_id": "%s", "algorithm": "rsa", "public_key": "%s",
                    "activated_at": null, "retired_at": null, "compromised_at": null } ] }
                """.formatted(keyId, publicKeyBase64));

        assertThatThrownBy(() -> AuditKeyRegistry.load(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported algorithm");
    }

    @Test
    void load_missingFile_isRejected() {
        assertThatThrownBy(() -> AuditKeyRegistry.load(directory.resolve("absent.json")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not readable");
    }

    @Test
    void load_malformedJson_isRejected() throws IOException {
        Path file = registryFile("{ not json");

        assertThatThrownBy(() -> AuditKeyRegistry.load(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void empty_resolvesNothing() {
        assertThat(AuditKeyRegistry.empty().find(keyId)).isEmpty();
        assertThat(AuditKeyRegistry.empty().size()).isZero();
    }
}
