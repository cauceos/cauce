package dev.cauce.llm.openai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The transcriber of an instance (ADR 0012, V3), bound from {@code cauce.speech.openai-compatible.*}:
 * off unless enabled, and with no key for a local server. The defaults are the quickstart's local
 * Whisper (speaches) and the model its optional profile pulls.
 */
@ConfigurationProperties(prefix = "cauce.speech.openai-compatible")
public class OpenAiCompatibleTranscriptionProperties {

    /** Whether the transcriber bean is created: off, voice notes are answered as unread messages. */
    private boolean enabled = false;

    /** The provider id its usage and price name. */
    private String providerId = "whisper";

    /** Base URL up to (but excluding) {@code /audio/transcriptions}. */
    private String baseUrl = "http://localhost:8000/v1";

    /** The model the server transcribes with. */
    private String model = "Systran/faster-whisper-small";

    /** A bearer key, for a hosted provider; empty for a local server. */
    private String apiKey = "";

    /** The expected language (ISO 639-1), or empty to let the server detect it. */
    private String language = "";

    /** Per-request HTTP timeout (and connect timeout): a CPU transcribes a 2-minute note in seconds. */
    private Duration timeout = Duration.ofSeconds(120);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
