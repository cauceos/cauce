package dev.cauce.api.channel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /v1/agents/{agentId}/channels}: binds a channel instance to
 * the agent. {@code channelType} must have a registered adapter (e.g. {@code "telegram"});
 * {@code credential} is the provider credential for that instance (Telegram: the bot token
 * from BotFather). Serialised in snake_case by the global Jackson naming strategy.
 */
public record CreateChannelConfigRequest(
        @NotBlank String channelType,
        @NotBlank @Size(max = 512) String credential) {
}
