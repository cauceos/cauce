package dev.cauce.api.webchat;

import dev.cauce.channels.webchat.ConsentMode;
import dev.cauce.channels.webchat.ResolvedWebChat;

/**
 * The widget's public configuration (ADR 0014, WC1): what it needs to show itself before the person
 * writes. It carries nothing about the visitor, and the AI notice is the widget's own fixed text, not
 * a setting. {@code active} false means the chat is switched off: the widget stays hidden or says so.
 */
public record WebChatConfigResponse(
        String publicKey,
        String turnstileSiteKey,
        String privacyPolicyUrl,
        ConsentMode consentMode,
        boolean consentRequired,
        boolean active) {

    static WebChatConfigResponse from(ResolvedWebChat channel) {
        return new WebChatConfigResponse(channel.publicKey(), channel.turnstileSiteKey(), channel.privacyPolicyUrl(),
                channel.consentMode(), channel.consentMode() == ConsentMode.ACCEPTANCE, channel.active());
    }
}
