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
        // A per-config webhook gets a secret, returned once; an app-routed number (WhatsApp) has
        // none of its own: its webhooks are verified with the app's secret (ADR 0006).
        if (config.isAppRouted() != (webhookSecret == null)) {
            throw new IllegalArgumentException("a webhook secret exists exactly for a per-config webhook");
        }
    }
}
