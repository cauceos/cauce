package dev.cauce.llm.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.llm.model.LlmInvocation;
import dev.cauce.llm.model.LlmResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmProviderRegistryTest {

    @Test
    void emptyRegistry_returnsEmptyAndNoProviders() {
        LlmProviderRegistry registry = new LlmProviderRegistry(List.of());

        assertThat(registry.getProvider("anthropic")).isEmpty();
        assertThat(registry.availableProviders()).isEmpty();
    }

    @Test
    void getProvider_returnsRegisteredProvider() {
        LlmProvider anthropic = fakeProvider("anthropic");
        LlmProviderRegistry registry = new LlmProviderRegistry(List.of(anthropic, fakeProvider("openai")));

        assertThat(registry.getProvider("anthropic")).contains(anthropic);
        assertThat(registry.availableProviders()).containsExactlyInAnyOrder("anthropic", "openai");
    }

    @Test
    void requireProvider_returnsProviderWhenPresent() {
        LlmProvider anthropic = fakeProvider("anthropic");
        LlmProviderRegistry registry = new LlmProviderRegistry(List.of(anthropic));

        assertThat(registry.requireProvider("anthropic")).isSameAs(anthropic);
    }

    @Test
    void requireProvider_throwsWhenMissing() {
        LlmProviderRegistry registry = new LlmProviderRegistry(List.of());

        assertThatThrownBy(() -> registry.requireProvider("anthropic"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("anthropic");
    }

    private static LlmProvider fakeProvider(String id) {
        return new LlmProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public LlmResponse invoke(LlmInvocation invocation) {
                throw new UnsupportedOperationException("not used in this test");
            }

            @Override
            public boolean supports(String modelName) {
                return false;
            }
        };
    }
}
