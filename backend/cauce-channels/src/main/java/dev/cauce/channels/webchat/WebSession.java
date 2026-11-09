package dev.cauce.channels.webchat;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * An anonymous web-chat session (ADR 0014, WC2): the person of a browser, for one web chat, until
 * {@code expiresAt}. Its id is the value of their {@code SESSION_ID} identity. The token that
 * authenticates it is never held here, only its hash in the database.
 */
public record WebSession(UUID id, UUID tenantId, UUID channelConfigId, Instant expiresAt, Instant consentedAt) {

    public WebSession {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(channelConfigId, "channelConfigId");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /** When the person accepted the privacy notice, for a channel that asks for acceptance. */
    public Optional<Instant> consented() {
        return Optional.ofNullable(consentedAt);
    }
}
