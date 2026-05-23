package dev.cauce.llm.spi;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Registry of the LLM providers available at runtime. Spring injects every
 * {@link LlmProvider} bean (the adapters that registered themselves), keyed by
 * {@link LlmProvider#id()}. If no adapter is active the registry is simply empty.
 */
@Component
public class LlmProviderRegistry {

    private final Map<String, LlmProvider> providersById;

    public LlmProviderRegistry(List<LlmProvider> providers) {
        this.providersById = providers.stream()
                .collect(Collectors.toUnmodifiableMap(LlmProvider::id, Function.identity()));
    }

    /** The provider for {@code providerId}, if registered. */
    public Optional<LlmProvider> getProvider(String providerId) {
        return Optional.ofNullable(providersById.get(providerId));
    }

    /**
     * The provider for {@code providerId}.
     *
     * @throws IllegalArgumentException if no provider is registered under that id
     */
    public LlmProvider requireProvider(String providerId) {
        LlmProvider provider = providersById.get(providerId);
        if (provider == null) {
            throw new IllegalArgumentException("No LLM provider registered for id '" + providerId
                    + "'. Available: " + availableProviders());
        }
        return provider;
    }

    /** The ids of all registered providers. */
    public Set<String> availableProviders() {
        return providersById.keySet();
    }
}
