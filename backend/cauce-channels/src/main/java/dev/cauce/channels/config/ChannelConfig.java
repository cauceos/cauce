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
 * token), needed by the outbound half. The domain holds it in clear; at rest it is sealed with
 * AES-GCM by the persistence mapper (ADR 0006, W3). {@code webhookSecretHash} is
 * the hash of the server-generated webhook secret; the plaintext is returned exactly once
 * at creation and verified on every webhook via the {@code ApiKeyHasher} port.
 *
 * <p>Two shapes, mirrored by the V31 CHECK (ADR 0006, W2): a <b>per-config webhook</b>
 * (Telegram) has a {@code webhookSecretHash} and no app binding; an <b>app-routed</b> number
 * (WhatsApp) has the {@code whatsappAppId} that receives its webhooks and its {@code phoneNumberId},
 * and no webhook secret of its own.
 */
public record ChannelConfig(UUID id,
                            UUID tenantId,
                            UUID agentId,
                            String channelType,
                            String credential,
                            String webhookSecretHash,
                            ChannelConfigStatus status,
                            Instant createdAt,
                            UUID whatsappAppId,
                            String phoneNumberId) {

    public ChannelConfig {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        requireText(channelType, "channelType");
        requireText(credential, "credential");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (whatsappAppId == null) {
            requireText(webhookSecretHash, "webhookSecretHash");
            if (phoneNumberId != null) {
                throw new IllegalArgumentException("phoneNumberId requires a whatsappAppId");
            }
        } else {
            requireText(phoneNumberId, "phoneNumberId");
            if (webhookSecretHash != null) {
                throw new IllegalArgumentException("an app-routed config has no webhook secret of its own");
            }
        }
    }

    /** A per-config-webhook config (no app binding). */
    public ChannelConfig(UUID id, UUID tenantId, UUID agentId, String channelType, String credential,
                         String webhookSecretHash, ChannelConfigStatus status, Instant createdAt) {
        this(id, tenantId, agentId, channelType, credential, webhookSecretHash, status, createdAt, null, null);
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

    /**
     * Binds the WhatsApp number {@code phoneNumberId}, served by the app {@code whatsappAppId}, to
     * {@code agentId} (ADR 0006, W2). {@code accessToken} is the credential replies are sent with.
     */
    public static ChannelConfig createWhatsAppNumber(UUID tenantId, UUID agentId, UUID whatsappAppId,
                                                     String phoneNumberId, String accessToken) {
        return new ChannelConfig(UuidGenerator.newV7(), tenantId, agentId, "whatsapp", accessToken, null,
                ChannelConfigStatus.ACTIVE, Instant.now(), Objects.requireNonNull(whatsappAppId, "whatsappAppId"),
                phoneNumberId == null ? null : phoneNumberId.strip());
    }

    /** Whether this config receives its webhooks through an app rather than its own URL. */
    public boolean isAppRouted() {
        return whatsappAppId != null;
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
