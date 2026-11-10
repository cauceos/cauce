package dev.cauce.channels.webchat;

import dev.cauce.channels.config.ChannelConfig;
import java.util.Objects;

/**
 * A web-chat channel: its binding, reached by its public key, its settings (ADR 0014, WC1), its look (W4)
 * and its shop-assistant mode (WC8), null for a chat without it.
 */
public record WebChatChannel(ChannelConfig config, WebChatSettings settings, WebChatAppearance appearance,
                             WebChatAssistant assistant) {

    /** A channel with the platform's neutral look. */
    public WebChatChannel(ChannelConfig config, WebChatSettings settings) {
        this(config, settings, WebChatAppearance.NEUTRAL);
    }

    /** A channel without the shop-assistant mode. */
    public WebChatChannel(ChannelConfig config, WebChatSettings settings, WebChatAppearance appearance) {
        this(config, settings, appearance, null);
    }

    public WebChatChannel {
        appearance = appearance == null ? WebChatAppearance.NEUTRAL : appearance;
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(settings, "settings");
        if (!config.isPublicKeyRouted() || !config.id().equals(settings.channelConfigId())) {
            throw new IllegalArgumentException("a web-chat channel is a public-key-routed config with its own settings");
        }
    }

    public String publicKey() {
        return config.publicKey();
    }
}
