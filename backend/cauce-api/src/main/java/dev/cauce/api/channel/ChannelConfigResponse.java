package dev.cauce.api.channel;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.cauce.channels.ChannelConfigCreationResult;
import java.time.Instant;
import java.util.UUID;

/**
 * Body of the {@code 201 Created} response to a channel binding. {@code webhookSecret} is
 * the plaintext secret, returned here exactly once and unrecoverable afterwards (only its
 * hash is stored) — the operator passes it to the provider (Telegram:
 * {@code setWebhook(url, secret_token)}). The webhook URL path is
 * {@code /webhooks/channels/{id}}. A WhatsApp number has no secret of its own (its webhooks are
 * the app's, ADR 0006) and carries its app and {@code phone_number_id} instead.
 */
public record ChannelConfigResponse(UUID id,
                                    UUID agentId,
                                    String channelType,
                                    String status,
                                    Instant createdAt,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) String webhookSecret,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) UUID whatsappAppId,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) String phoneNumberId) {

    public static ChannelConfigResponse from(ChannelConfigCreationResult result) {
        return new ChannelConfigResponse(
                result.config().id(),
                result.config().agentId(),
                result.config().channelType(),
                result.config().status().name(),
                result.config().createdAt(),
                result.webhookSecret(),
                result.config().whatsappAppId(),
                result.config().phoneNumberId());
    }
}
