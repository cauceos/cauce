package dev.cauce.governance.audit.signing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code key_id} → public key registry, loaded from a JSON file OUTSIDE the database.
 *
 * <p>Outside the database is the whole point. The hash chain already detects alteration by
 * someone who cannot recompute it; signatures exist to detect alteration by someone who can —
 * an actor holding database access. If the public keys lived in the same database, that actor
 * would simply substitute one and have their forgeries verify. The registry is published by
 * each operator, alongside their deployment; there is no central authority, only a format.
 *
 * <p>File format:
 * <pre>{@code
 * { "registry_version": 1,
 *   "keys": [
 *     { "key_id": "9f2a1c4e8b7d0355",
 *       "algorithm": "ed25519",
 *       "public_key": "MCowBQYDK2VwAyEA...",   // base64 SubjectPublicKeyInfo (PEM body)
 *       "activated_at": "2026-09-11T00:00:00Z",
 *       "retired_at": null,
 *       "compromised_at": null }
 *   ] }
 * }</pre>
 *
 * <p>Entries are only ever ADDED. Retiring a key means its private half stops signing; its
 * public half must outlive it or everything it signed becomes unverifiable. The declared
 * {@code key_id} is checked against the one derived from the public key itself, so a registry
 * that names a key it does not actually contain is rejected at startup rather than silently
 * failing verification later.
 *
 * <p>Loaded once, at construction. A registry change needs a restart — stated plainly here
 * rather than pretending to hot-reload. An instance with no registry configured is a valid
 * configuration: it simply cannot check any signature, which verification reports as
 * unverifiable coverage rather than as a broken chain.
 */
public class AuditKeyRegistry {

    private static final String ED25519 = "Ed25519";

    private final Map<String, AuditSigningKey> keysById;

    private AuditKeyRegistry(Map<String, AuditSigningKey> keysById) {
        this.keysById = Map.copyOf(keysById);
    }

    /** An empty registry: no public key is known, so no signature can be checked. */
    public static AuditKeyRegistry empty() {
        return new AuditKeyRegistry(Map.of());
    }

    /** A registry over the given keys, for the startup self-test and for tests. */
    public static AuditKeyRegistry of(AuditSigningKey... keys) {
        Map<String, AuditSigningKey> byId = new LinkedHashMap<>();
        for (AuditSigningKey key : keys) {
            byId.put(key.keyId(), key);
        }
        return new AuditKeyRegistry(byId);
    }

    /** Loads the registry from {@code path}, failing fast on anything malformed. */
    public static AuditKeyRegistry load(Path path) {
        byte[] content;
        try {
            content = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new IllegalStateException("audit key registry is not readable: " + path, e);
        }
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(content);
        } catch (IOException e) {
            throw new IllegalStateException("audit key registry is not valid JSON: " + path, e);
        }
        JsonNode keys = root.path("keys");
        if (!keys.isArray()) {
            throw new IllegalStateException("audit key registry has no \"keys\" array: " + path);
        }
        Map<String, AuditSigningKey> loaded = new LinkedHashMap<>();
        for (JsonNode node : keys) {
            AuditSigningKey key = parseKey(node, path);
            if (loaded.put(key.keyId(), key) != null) {
                throw new IllegalStateException(
                        "audit key registry declares key_id twice: " + key.keyId());
            }
        }
        return new AuditKeyRegistry(loaded);
    }

    private static AuditSigningKey parseKey(JsonNode node, Path path) {
        String declaredId = text(node, "key_id", path);
        String algorithm = text(node, "algorithm", path);
        if (!ED25519.equalsIgnoreCase(algorithm)) {
            throw new IllegalStateException(
                    "audit key registry has an unsupported algorithm: " + algorithm);
        }
        byte[] encoded;
        try {
            encoded = Base64.getDecoder().decode(text(node, "public_key", path));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "audit key registry public_key is not valid base64: " + declaredId, e);
        }
        PublicKey publicKey;
        try {
            publicKey = KeyFactory.getInstance(ED25519)
                    .generatePublic(new X509EncodedKeySpec(encoded));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException(
                    "audit key registry public_key is not an Ed25519 key: " + declaredId, e);
        }
        String derivedId = AuditKeyId.of(publicKey);
        if (!derivedId.equals(declaredId)) {
            throw new IllegalStateException("audit key registry key_id does not match its "
                    + "public key: declared " + declaredId + ", derived " + derivedId);
        }
        return new AuditSigningKey(declaredId, publicKey, instant(node, "activated_at"),
                instant(node, "retired_at"), instant(node, "compromised_at"));
    }

    private static String text(JsonNode node, String field, Path path) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException(
                    "audit key registry entry is missing \"" + field + "\": " + path);
        }
        return value.asText();
    }

    private static Instant instant(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isNull() || value.isMissingNode()) {
            return null;
        }
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException e) {
            throw new IllegalStateException(
                    "audit key registry \"" + field + "\" is not an ISO-8601 instant", e);
        }
    }

    /** The key named by {@code keyId}, or empty when this instance cannot resolve it. */
    public Optional<AuditSigningKey> find(String keyId) {
        return keyId == null ? Optional.empty() : Optional.ofNullable(keysById.get(keyId));
    }

    /** How many keys this registry knows. */
    public int size() {
        return keysById.size();
    }
}
