package dev.cauce.channels.webchat;

import dev.cauce.channels.config.ChannelConfig;
import java.util.Objects;

/** A web-chat channel: its binding, reached by its public key, and its settings (ADR 0014, WC1). */
public record WebChatChannel(ChannelConfig config, WebChatSettings settings) {

    public WebChatChannel {
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
