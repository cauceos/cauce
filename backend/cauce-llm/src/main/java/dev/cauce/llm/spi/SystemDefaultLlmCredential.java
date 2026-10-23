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
        this.apiKeyVar = apiKeyVariable(providerId);
        this.organizationVar = providerId.toUpperCase(Locale.ROOT) + "_ORG_ID";
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    /**
     * The variable this credential reads the key of {@code providerId} from: {@code <PROVIDER>_API_KEY}.
     * Public so that a provider found missing can be reported by the setting it lacks.
     */
    public static String apiKeyVariable(String providerId) {
        return Objects.requireNonNull(providerId, "providerId").toUpperCase(Locale.ROOT) + "_API_KEY";
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
