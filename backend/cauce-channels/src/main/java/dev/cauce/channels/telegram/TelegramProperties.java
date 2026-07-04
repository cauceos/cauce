package dev.cauce.channels.telegram;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Telegram adapter, bound from {@code cauce.channels.telegram.*}.
 * The bot token is intentionally not here: it is per channel instance and lives on the
 * {@code ChannelConfig} credential.
 */
@ConfigurationProperties(prefix = "cauce.channels.telegram")
public class TelegramProperties {

    /** Base URL of the Telegram Bot API (overridable for tests). */
    private String baseUrl = "https://api.telegram.org";

    /** Per-request HTTP timeout for outbound deliveries. */
    private Duration timeout = Duration.ofSeconds(10);

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
}
