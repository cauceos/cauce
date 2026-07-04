package dev.cauce.api.channel;

import dev.cauce.channels.ChannelConfigCreationResult;
import java.time.Instant;
import java.util.UUID;

/**
 * Body of the {@code 201 Created} response to a channel binding. {@code webhookSecret} is
 * the plaintext secret, returned here exactly once and unrecoverable afterwards (only its
 * hash is stored) — the operator passes it to the provider (Telegram:
 * {@code setWebhook(url, secret_token)}). The webhook URL path is
 * {@code /webhooks/channels/{id}}.
 */
public record ChannelConfigResponse(UUID id,
                                    UUID agentId,
                                    String channelType,
                                    String status,
                                    Instant createdAt,
                                    String webhookSecret) {

    public static ChannelConfigResponse from(ChannelConfigCreationResult result) {
        return new ChannelConfigResponse(
                result.config().id(),
                result.config().agentId(),
                result.config().channelType(),
                result.config().status().name(),
                result.config().createdAt(),
                result.webhookSecret());
    }
}
