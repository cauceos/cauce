package dev.cauce.channels.whatsapp;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings of the WhatsApp Cloud API channel ({@code cauce.channels.whatsapp.*}, ADR 0006). */
@ConfigurationProperties(prefix = "cauce.channels.whatsapp")
public class WhatsAppProperties {

    /** Base URL of the Graph API (overridable for tests). */
    private String baseUrl = "https://graph.facebook.com";

    /** Graph API version the requests are pinned to. */
    private String apiVersion = "v24.0";

    /** Per-request HTTP timeout for outbound sends. */
    private Duration timeout = Duration.ofSeconds(10);

    /**
     * W5: the fixed reply to an inbound message that is not text — never silence. A platform
     * setting only (ADR 0006 records why there is no per-agent override yet).
     */
    private String unsupportedMessageReply =
            "Sorry, for now I can only read text messages. Please write your message as text.";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public void setApiVersion(String apiVersion) {
        this.apiVersion = apiVersion;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public String getUnsupportedMessageReply() {
        return unsupportedMessageReply;
    }

    public void setUnsupportedMessageReply(String unsupportedMessageReply) {
        if (unsupportedMessageReply == null || unsupportedMessageReply.isBlank()) {
            throw new IllegalArgumentException("cauce.channels.whatsapp.unsupported-message-reply must not be blank");
        }
        this.unsupportedMessageReply = unsupportedMessageReply.strip();
    }
}
