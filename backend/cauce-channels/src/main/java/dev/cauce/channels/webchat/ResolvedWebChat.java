package dev.cauce.channels.webchat;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * A web-chat channel as its public key resolves it, before any tenant context exists (ADR 0014,
 * WC1): what deciding a browser's request needs, never the credential. A disabled channel resolves
 * too ({@code active} false), so its widget gets a clear refusal rather than an unknown key. The
 * caller establishes {@code tenantId} as the context and everything after runs under RLS.
 */
public record ResolvedWebChat(UUID channelConfigId, UUID tenantId, UUID agentId, String publicKey,
                              List<String> allowedOrigins, String turnstileSiteKey, String privacyPolicyUrl,
                              ConsentMode consentMode, boolean active, WebChatLimits limits) {

    /** A channel of the given status that keeps the platform's limits. */
    public ResolvedWebChat(UUID channelConfigId, UUID tenantId, UUID agentId, String publicKey,
                           List<String> allowedOrigins, String turnstileSiteKey, String privacyPolicyUrl,
                           ConsentMode consentMode, boolean active) {
        this(channelConfigId, tenantId, agentId, publicKey, allowedOrigins, turnstileSiteKey, privacyPolicyUrl,
                consentMode, active, WebChatLimits.NONE);
    }

    /** An active channel (the shape before the emergency switch). */
    public ResolvedWebChat(UUID channelConfigId, UUID tenantId, UUID agentId, String publicKey,
                           List<String> allowedOrigins, String turnstileSiteKey, String privacyPolicyUrl,
                           ConsentMode consentMode) {
        this(channelConfigId, tenantId, agentId, publicKey, allowedOrigins, turnstileSiteKey, privacyPolicyUrl,
                consentMode, true);
    }

    /**
     * This channel, if it takes new sessions and messages.
     *
     * @throws ChannelDisabledException if it is switched off
     */
    public ResolvedWebChat requireActive() {
        if (!active) {
            throw new ChannelDisabledException("This chat is switched off for now");
        }
        return this;
    }

    /** The compact constructor of the full shape. */
    public ResolvedWebChat {

        Objects.requireNonNull(channelConfigId, "channelConfigId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(publicKey, "publicKey");
        allowedOrigins = List.copyOf(allowedOrigins);
        Objects.requireNonNull(consentMode, "consentMode");
        limits = limits == null ? WebChatLimits.NONE : limits;
    }

    /** Whether a browser on {@code origin} may call this channel: an exact match, ignoring case. */
    public boolean allows(String origin) {
        return origin != null && allowedOrigins.contains(origin.toLowerCase(Locale.ROOT));
    }
}
