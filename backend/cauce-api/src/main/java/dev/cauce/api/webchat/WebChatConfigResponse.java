package dev.cauce.api.webchat;

import dev.cauce.channels.webchat.ConsentMode;
import dev.cauce.channels.webchat.ResolvedWebChat;
import dev.cauce.channels.webchat.WebChatAppearance;
import dev.cauce.channels.webchat.WebChatAssistant;

/**
 * The widget's public configuration (ADR 0014, WC1): what it needs to show itself before the person
 * writes. It carries nothing about the visitor, and the AI notice is the widget's own fixed text, not
 * a setting. {@code active} false means the chat is switched off: the widget stays hidden or says so.
 * {@code assistant} is the shop-assistant mode (WC8) when it is on, null otherwise.
 */
public record WebChatConfigResponse(
        String publicKey,
        String turnstileSiteKey,
        String privacyPolicyUrl,
        ConsentMode consentMode,
        boolean consentRequired,
        boolean active,
        WebChatAppearance appearance,
        WebChatAssistant assistant) {

    static WebChatConfigResponse from(ResolvedWebChat channel) {
        return new WebChatConfigResponse(channel.publicKey(), channel.turnstileSiteKey(), channel.privacyPolicyUrl(),
                channel.consentMode(), channel.consentMode() == ConsentMode.ACCEPTANCE, channel.active(),
                channel.appearance(), channel.assistant() != null && channel.assistant().enabled()
                        ? channel.assistant() : null);
    }
}
