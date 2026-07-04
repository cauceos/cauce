package dev.cauce.channels;

import dev.cauce.channels.config.ChannelConfig;
import java.util.Objects;

/**
 * The outcome of creating a channel config: the persisted binding plus the webhook secret
 * in plaintext — readable here and never again (only its hash is stored). Mirror of
 * {@code ApiKeyCreationResult}.
 */
public record ChannelConfigCreationResult(ChannelConfig config, String webhookSecret) {

    public ChannelConfigCreationResult {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(webhookSecret, "webhookSecret must not be null");
    }
}
