package dev.cauce.channels.webchat;

import dev.cauce.channels.config.ChannelConfig;
import java.util.Objects;

/** A web-chat channel: its binding, reached by its public key, and its settings (ADR 0014, WC1). */
public record WebChatChannel(ChannelConfig config, WebChatSettings settings, WebChatAppearance appearance) {

    /** A channel with the platform's neutral look. */
    public WebChatChannel(ChannelConfig config, WebChatSettings settings) {
        this(config, settings, WebChatAppearance.NEUTRAL);
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
