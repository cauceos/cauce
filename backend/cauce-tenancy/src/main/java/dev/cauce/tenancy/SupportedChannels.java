package dev.cauce.tenancy;

import dev.cauce.core.conversation.InvalidChannelTypeException;
import java.util.Set;

/**
 * The temporary, hardcoded set of channel types the application services accept. Shared by
 * {@link IdentityService} (an identity is keyed by channel) and {@link ConversationService}
 * (a conversation is still carried by a channel until it is re-keyed to an identity).
 *
 * <p>{@code "api"} is the reserved built-in channel for the REST messaging endpoint: a
 * first-class origin handled in-process, not a pluggable adapter. {@code "telegram"} has a
 * real adapter in cauce-channels; the rest are placeholders.
 *
 * <p>TODO: replace with the cauce-channels {@code ChannelAdapterRegistry}. Not doable by
 * direct dependency (cycle: tenancy &lt;- orchestration &lt;- channels); needs a port in core
 * or an app-level validation seam. Registered in {@code docs/deferred.md}.
 */
final class SupportedChannels {

    private static final Set<String> SUPPORTED =
            Set.of("api", "telegram", "whatsapp", "voice", "email", "web_chat");

    private SupportedChannels() {
    }

    /** @throws InvalidChannelTypeException if {@code channelType} is not in the supported set */
    static void requireSupported(String channelType) {
        if (!SUPPORTED.contains(channelType)) {
            throw new InvalidChannelTypeException("Unsupported channel type: " + channelType);
        }
    }
}
