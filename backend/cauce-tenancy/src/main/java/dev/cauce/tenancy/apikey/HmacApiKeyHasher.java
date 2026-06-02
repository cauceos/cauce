package dev.cauce.tenancy.apikey;

import dev.cauce.core.apikey.ApiKeyHasher;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * Hashes API keys with HMAC-SHA256 keyed by a server-side pepper.
 *
 * <p>API keys are already high-entropy random secrets, so a slow salted password hash (bcrypt) buys
 * nothing here: there is no low-entropy password to brute-force and no need for a per-key salt. A
 * keyed HMAC is the right primitive — fast and constant-work — and the pepper means a leaked
 * database alone cannot verify guessed keys without also stealing the pepper.
 *
 * <p>The hash is deterministic (same key + pepper → same digest), which is what the prefix-lookup +
 * verify path relies on. {@link #matches} compares in constant time. The pepper is STABLE: rotating
 * it invalidates every existing key. It lives only in configuration (dev/test default, prod from the
 * environment), never in the database or version control.
 */
@Component
public class HmacApiKeyHasher implements ApiKeyHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec pepperKey;

    public HmacApiKeyHasher(ApiKeyPepperProperties properties) {
        this.pepperKey = new SecretKeySpec(
                properties.getApiKeyPepper().getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    @Override
    public String hash(String plaintext) {
        return HexFormat.of().formatHex(mac(plaintext));
    }

    @Override
    public boolean matches(String plaintext, String hash) {
        if (plaintext == null || hash == null) {
            return false;
        }
        byte[] actual;
        try {
            actual = HexFormat.of().parseHex(hash);
        } catch (IllegalArgumentException notHex) {
            return false;
        }
        return MessageDigest.isEqual(mac(plaintext), actual);
    }

    private byte[] mac(String plaintext) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(pepperKey);
            return mac.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable or pepper invalid", e);
        }
    }

    /** Registers {@link ApiKeyPepperProperties} for binding (mirrors {@code ApiKeyCache.CacheConfig}). */
    @Configuration
    @EnableConfigurationProperties(ApiKeyPepperProperties.class)
    static class HasherConfig {
    }
}
