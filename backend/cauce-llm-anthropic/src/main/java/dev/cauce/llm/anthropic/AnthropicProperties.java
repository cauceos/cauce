package dev.cauce.llm.anthropic;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Anthropic adapter, bound from {@code cauce.llm.anthropic.*}.
 * The API key is intentionally not here: it is read from the environment
 * ({@code ANTHROPIC_API_KEY}) and supplied per invocation via the SPI credential.
 */
@ConfigurationProperties(prefix = "cauce.llm.anthropic")
public class AnthropicProperties {

    /** Whether the adapter is active (a provider bean is created only when also given a key). */
    private boolean enabled = true;

    /** Base URL of the Anthropic API (overridable for tests). */
    private String baseUrl = "https://api.anthropic.com";

    /** Value of the required {@code anthropic-version} header. */
    private String version = "2023-06-01";

    /** Per-request HTTP timeout. */
    private Duration timeout = Duration.ofSeconds(60);

    /** {@code max_tokens} used when an invocation does not specify one (Anthropic requires it). */
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

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
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
