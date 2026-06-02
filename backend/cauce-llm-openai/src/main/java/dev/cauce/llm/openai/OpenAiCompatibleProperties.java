package dev.cauce.llm.openai;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the OpenAI-compatible adapter, bound from
 * {@code cauce.llm.openai-compatible.providers.<id>.*}. Each entry configures one endpoint
 * (e.g. {@code openai}, {@code mistral}, {@code ollama}); the agent's {@code modelProvider} selects
 * which one. API keys are intentionally not here — they are read from the environment
 * ({@code OPENAI_API_KEY}, {@code MISTRAL_API_KEY}; none for Ollama) and supplied per invocation via
 * the SPI credential.
 */
@ConfigurationProperties(prefix = "cauce.llm.openai-compatible")
public class OpenAiCompatibleProperties {

    /** Per-provider settings, keyed by provider id (e.g. {@code ollama}). */
    private Map<String, ProviderSettings> providers = new LinkedHashMap<>();

    public Map<String, ProviderSettings> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, ProviderSettings> providers) {
        this.providers = providers;
    }

    /** Settings for a single OpenAI-compatible endpoint. */
    public static class ProviderSettings {

        /** Whether a provider bean is created for this id. */
        private boolean enabled = false;

        /** Base URL up to (but excluding) {@code /chat/completions}. Null falls back to a built-in default. */
        private String baseUrl;

        /** Per-request HTTP timeout (and connect timeout). */
        private Duration timeout = Duration.ofSeconds(60);

        /** {@code max_tokens} used when an invocation does not specify one. */
        private int maxTokens = 4096;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }
    }
}
