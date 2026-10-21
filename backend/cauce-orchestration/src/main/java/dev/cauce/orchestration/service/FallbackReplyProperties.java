package dev.cauce.orchestration.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Platform settings of the fallback reply ({@code cauce.orchestration.fallback.*}, ADR 0005,
 * C3): the text the interlocutor receives when an invocation fails for good and the agent has
 * none of its own. It must never carry internal detail; it is the same text for every failure.
 */
@ConfigurationProperties(prefix = "cauce.orchestration.fallback")
public class FallbackReplyProperties {

    /** Platform default of {@link #getDefaultReply()}. */
    public static final String DEFAULT_REPLY =
            "Sorry, I could not answer your message this time. Please try again later.";

    private String defaultReply = DEFAULT_REPLY;

    public String getDefaultReply() {
        return defaultReply;
    }

    public void setDefaultReply(String defaultReply) {
        if (defaultReply == null || defaultReply.isBlank()) {
            throw new IllegalArgumentException("cauce.orchestration.fallback.default-reply must not be blank");
        }
        this.defaultReply = defaultReply.strip();
    }
}
