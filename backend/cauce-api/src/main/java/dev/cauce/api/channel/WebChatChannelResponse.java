package dev.cauce.api.channel;

import dev.cauce.channels.config.ChannelConfigStatus;
import dev.cauce.channels.webchat.ConsentMode;
import dev.cauce.channels.webchat.WebChatAppearance;
import dev.cauce.channels.webchat.WebChatLimits;
import dev.cauce.channels.webchat.WebChatChannel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A web-chat channel as the API shows it (ADR 0014, WC1): its public key, which the shop embeds in its
 * page, and its settings. The Turnstile secret never appears.
 */
public record WebChatChannelResponse(
        UUID id,
        UUID tenantId,
        UUID agentId,
        String channelType,
        String publicKey,
        List<String> allowedOrigins,
        String turnstileSiteKey,
        String privacyPolicyUrl,
        ConsentMode consentMode,
        ChannelConfigStatus status,
        WebChatLimits limits,
        WebChatAppearance appearance,
        Instant createdAt) {

    public static WebChatChannelResponse from(WebChatChannel channel) {
        return new WebChatChannelResponse(channel.config().id(), channel.config().tenantId(),
                channel.config().agentId(), channel.config().channelType(), channel.publicKey(),
                channel.settings().allowedOrigins(), channel.settings().turnstileSiteKey(),
                channel.settings().privacyPolicyUrl(), channel.settings().consentMode(), channel.config().status(),
                channel.settings().limits(), channel.appearance(), channel.config().createdAt());
    }
}
