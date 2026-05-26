package dev.cauce.tenancy.apikey;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Exposes the {@link PasswordEncoder} used by {@link BcryptApiKeyHasher}. Kept as a
 * separate {@link Configuration} so a test slice can override the encoder (e.g. with
 * a faster {@code NoOpPasswordEncoder} subclass) without touching the hasher.
 *
 * <p>BCrypt cost defaults to 10 (~100ms per hash on a modern CPU). The asynchronous
 * cost matters on the hot path; the {@code ApiKeyCache} amortises it across the TTL.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder apiKeyPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
