package dev.cauce.api.channel;

import dev.cauce.channels.webchat.ConsentMode;
import dev.cauce.channels.webchat.WebChatAppearance;
import dev.cauce.channels.webchat.WebChatLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Body of {@code POST /v1/agents/{agentId}/web-chat-channels} (ADR 0014, WC1): the Turnstile secret
 * (sealed at rest, never returned) and site key, the exact origins a browser may call the channel
 * from, the shop's privacy policy and how consent is asked for ({@code NOTICE} by default).
 */
public record CreateWebChatChannelRequest(
        @NotBlank @Size(max = 512) String turnstileSecret,
        @NotBlank @Size(max = 100) String turnstileSiteKey,
        @NotEmpty @Size(max = 20) List<String> allowedOrigins,
        @NotBlank @Size(max = 500) String privacyPolicyUrl,
        ConsentMode consentMode,
        Limits limits,
        WebChatAppearance appearance) {

    /**
     * The channel's own request limits (ADR 0014, WC3): each optional, positive and no higher than the
     * platform's; an absent one keeps the platform's.
     */
    public record Limits(Integer sessionsPerIpHour, Integer messagesPerSessionMinute, Integer messagesPerSessionDay,
                         Integer messagesPerIpMinute, Integer readsPerSessionMinute) {

        WebChatLimits toDomain() {
            return new WebChatLimits(sessionsPerIpHour, messagesPerSessionMinute, messagesPerSessionDay,
                    messagesPerIpMinute, readsPerSessionMinute);
        }
    }
}
