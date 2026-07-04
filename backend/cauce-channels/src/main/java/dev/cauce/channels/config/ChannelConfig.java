package dev.cauce.channels.config;

import dev.cauce.core.UuidGenerator;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The binding of one channel instance (e.g. a Telegram bot) to an agent: inbound traffic
 * addressed to this config is ingested for that agent, under that agent's tenant.
 *
 * <p>Pure domain type: no persistence or framework dependencies. Immutable; created via
 * {@link #create} with status {@link ChannelConfigStatus#ACTIVE} and a time-ordered UUIDv7
 * id.
 *
 * <p>{@code tenantId} is the owning (CLIENT) tenant of the bound agent, resolved at
 * creation — not the tenant that happens to create the binding: it is what hierarchical
 * Row-Level Security keys on, and what the unauthenticated webhook path needs to establish
 * the tenant context before ingesting (same rationale as {@code PendingInvocation}).
 *
 * <p>{@code credential} is the provider credential (Telegram bot token; WhatsApp access
 * token), needed by the outbound half. Stored at rest as-is for now — encryption-at-rest
 * is deferred alongside per-tenant LLM credentials (TODO). {@code webhookSecretHash} is
 * the hash of the server-generated webhook secret; the plaintext is returned exactly once
 * at creation and verified on every webhook via the {@code ApiKeyHasher} port.
 */
public record ChannelConfig(UUID id,
                            UUID tenantId,
                            UUID agentId,
                            String channelType,
                            String credential,
                            String webhookSecretHash,
                            ChannelConfigStatus status,
                            Instant createdAt) {

    public ChannelConfig {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        requireText(channelType, "channelType");
        requireText(credential, "credential");
        requireText(webhookSecretHash, "webhookSecretHash");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    /**
     * Binds a new channel instance to {@code agentId}, owned by {@code tenantId} (the
     * agent's tenant). Starts ACTIVE with a fresh UUIDv7.
     */
    public static ChannelConfig create(UUID tenantId, UUID agentId, String channelType,
                                       String credential, String webhookSecretHash) {
        return new ChannelConfig(UuidGenerator.newV7(), tenantId, agentId, channelType,
                credential, webhookSecretHash, ChannelConfigStatus.ACTIVE, Instant.now());
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
