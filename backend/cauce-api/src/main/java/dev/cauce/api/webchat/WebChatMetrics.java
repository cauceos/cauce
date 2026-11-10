package dev.cauce.api.webchat;

import dev.cauce.channels.webchat.ResolvedWebChat;
import dev.cauce.channels.webchat.WebChatAssistant;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The public web-chat surface's meters (ADR 0014; ADR 0011), what the operator's alerts read: every
 * response by its status code, and each request refused because the request limiter could not be asked.
 * Tags are the status code only, a bounded set; never a channel, a session or an IP.
 *
 * <p>The shop-assistant mode's meters (WC8) compare variants, so they are tagged by channel config and
 * variant: a deployment's web chats are few, and a channel declares at most five variants; anything
 * else reads as {@code none}. Never a session, a person or an IP.
 */
@Component
public class WebChatMetrics {

    static final String RESPONSES = "cauce.web.responses";
    static final String LIMITER_UNAVAILABLE = "cauce.web.rate_limit.unavailable";
    static final String OPENINGS = "cauce.web.openings";
    static final String CONVERSATIONS = "cauce.web.conversations";
    static final String NONE = "none";

    private final MeterRegistry registry;

    public WebChatMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** One response of the surface, by its status code. */
    public void response(int status) {
        registry.counter(RESPONSES, "status", String.valueOf(status)).increment();
    }

    /** One request refused because Redis could not be asked (503 {@code rate_limit_unavailable}). */
    public void limiterUnavailable() {
        registry.counter(LIMITER_UNAVAILABLE).increment();
    }

    /** {@code variant} if the channel's shop-assistant mode declares it, else {@code none}. */
    public String variantOf(ResolvedWebChat channel, String variant) {
        return channel.assistant() != null && channel.assistant().variant(variant).isPresent() ? variant : NONE;
    }

    /**
     * The chat opened before a message: {@code proactive} on a signal, or {@code on_demand}. Counted with
     * the message that followed it (WC8: an opening that ends without a message is not counted here).
     */
    public void opening(ResolvedWebChat channel, String variant, String kind, String signal) {
        boolean proactive = "proactive".equals(kind);
        if (!proactive && !"on_demand".equals(kind)) {
            return;
        }
        registry.counter(OPENINGS, "channel_config", channel.channelConfigId().toString(), "variant", variant,
                "kind", kind, "signal", proactive && WebChatAssistant.SIGNALS.contains(signal) ? signal : NONE)
                .increment();
    }

    /** A thread begun on the channel. */
    public void conversation(ResolvedWebChat channel, String variant) {
        registry.counter(CONVERSATIONS, "channel_config", channel.channelConfigId().toString(), "variant", variant)
                .increment();
    }
}
