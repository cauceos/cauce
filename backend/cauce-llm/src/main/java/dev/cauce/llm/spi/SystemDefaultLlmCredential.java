package dev.cauce.llm.spi;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.core.env.Environment;

/**
 * System-wide (Phase 1) credential that resolves a provider's key from the Spring
 * {@link Environment}, reading {@code <PROVIDER>_API_KEY} and the optional
 * {@code <PROVIDER>_ORG_ID} (e.g. {@code ANTHROPIC_API_KEY}). Built with the provider id;
 * Phase 2 will introduce per-tenant credentials behind the same {@link LlmCredential}
 * interface without changing the SPI signature.
 */
public final class SystemDefaultLlmCredential implements LlmCredential {

    private final String apiKeyVar;
    private final String organizationVar;
    private final Environment environment;

    public SystemDefaultLlmCredential(String providerId, Environment environment) {
        String prefix = Objects.requireNonNull(providerId, "providerId")
                .toUpperCase(Locale.ROOT);
        this.apiKeyVar = prefix + "_API_KEY";
        this.organizationVar = prefix + "_ORG_ID";
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @Override
    public String getApiKey() {
        return environment.getProperty(apiKeyVar);
    }

    @Override
    public Optional<String> getOrganizationId() {
        return Optional.ofNullable(environment.getProperty(organizationVar))
                .filter(value -> !value.isBlank());
    }
}
