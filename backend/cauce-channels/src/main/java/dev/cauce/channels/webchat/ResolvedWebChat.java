package dev.cauce.channels.webchat;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * An ACTIVE web-chat channel as its public key resolves it, before any tenant context exists (ADR
 * 0014, WC1): what deciding a browser's request needs, never the credential. The caller establishes
 * {@code tenantId} as the context and everything after runs under RLS.
 */
public record ResolvedWebChat(UUID channelConfigId, UUID tenantId, UUID agentId, String publicKey,
                              List<String> allowedOrigins, String turnstileSiteKey, String privacyPolicyUrl,
                              ConsentMode consentMode) {

    public ResolvedWebChat {
        Objects.requireNonNull(channelConfigId, "channelConfigId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(publicKey, "publicKey");
        allowedOrigins = List.copyOf(allowedOrigins);
        Objects.requireNonNull(consentMode, "consentMode");
    }

    /** Whether a browser on {@code origin} may call this channel: an exact match, ignoring case. */
    public boolean allows(String origin) {
        return origin != null && allowedOrigins.contains(origin.toLowerCase(Locale.ROOT));
    }
}
