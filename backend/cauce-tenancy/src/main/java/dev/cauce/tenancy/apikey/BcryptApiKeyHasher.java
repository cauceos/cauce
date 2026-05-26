package dev.cauce.tenancy.apikey;

import dev.cauce.core.apikey.ApiKeyHasher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Bcrypt-backed implementation of the {@link ApiKeyHasher} port. Uses Spring Security's
 * {@link PasswordEncoder} (configured in {@link PasswordEncoderConfig} as a
 * {@code BCryptPasswordEncoder}) so the encoder can be swapped or tuned without
 * touching either the domain or the call sites.
 *
 * <p>Bcrypt's {@code matches} is constant-time relative to the contents of the input,
 * which is what protects against timing attacks on individual characters.
 */
@Component
public class BcryptApiKeyHasher implements ApiKeyHasher {

    private final PasswordEncoder passwordEncoder;

    public BcryptApiKeyHasher(PasswordEncoder apiKeyPasswordEncoder) {
        this.passwordEncoder = apiKeyPasswordEncoder;
    }

    @Override
    public String hash(String plaintext) {
        return passwordEncoder.encode(plaintext);
    }

    @Override
    public boolean matches(String plaintext, String hash) {
        if (plaintext == null || hash == null) {
            return false;
        }
        return passwordEncoder.matches(plaintext, hash);
    }
}
