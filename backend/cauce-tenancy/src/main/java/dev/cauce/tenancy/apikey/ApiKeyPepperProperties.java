package dev.cauce.tenancy.apikey;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The server-side pepper used to HMAC API keys. Bound from {@code cauce.security.api-key-pepper}.
 *
 * <p>The default is a non-secret value for local dev and tests; <strong>production MUST override it
 * from the environment</strong> and never commit it. The pepper is STABLE infrastructure: changing
 * it invalidates every existing key (their stored HMACs no longer match).
 */
@ConfigurationProperties(prefix = "cauce.security")
public class ApiKeyPepperProperties {

    /** HMAC pepper; the dev/test default is intentionally non-secret. */
    private String apiKeyPepper = "dev-only-insecure-api-key-pepper";

    public String getApiKeyPepper() {
        return apiKeyPepper;
    }

    public void setApiKeyPepper(String apiKeyPepper) {
        this.apiKeyPepper = apiKeyPepper;
    }
}
