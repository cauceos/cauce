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
 * <p>Three shapes, mirrored by the V59 CHECK: a <b>per-config webhook</b> (Telegram) has a
 * {@code webhookSecretHash} and no app binding; an <b>app-routed</b> number (WhatsApp, ADR 0006, W2)
 * has the {@code whatsappAppId} that receives its webhooks and its {@code phoneNumberId}, and no
 * webhook secret of its own; a <b>public-key-routed</b> channel (web chat, ADR 0014, WC1) has the
 * {@code publicKey} a browser reaches it with, and neither. A web chat's credential is its Turnstile
 * secret.
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
                            String phoneNumberId,
                            String publicKey) {

    public ChannelConfig {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        requireText(channelType, "channelType");
        requireText(credential, "credential");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (publicKey != null) {
            requireText(publicKey, "publicKey");
            if (webhookSecretHash != null || whatsappAppId != null || phoneNumberId != null) {
                throw new IllegalArgumentException("a public-key-routed config has no webhook secret and no app");
            }
        } else if (whatsappAppId == null) {
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
        this(id, tenantId, agentId, channelType, credential, webhookSecretHash, status, createdAt, null, null, null);
    }

    /** A per-config-webhook or app-routed config (no public key). */
    public ChannelConfig(UUID id, UUID tenantId, UUID agentId, String channelType, String credential,
                         String webhookSecretHash, ChannelConfigStatus status, Instant createdAt,
                         UUID whatsappAppId, String phoneNumberId) {
        this(id, tenantId, agentId, channelType, credential, webhookSecretHash, status, createdAt, whatsappAppId,
                phoneNumberId, null);
    }

    /**
     * Binds a new web-chat channel to {@code agentId} (ADR 0014, WC1): reached by browsers through
     * {@code publicKey}, with {@code turnstileSecret} as its credential.
     */
    public static ChannelConfig createWebChat(UUID tenantId, UUID agentId, String publicKey, String turnstileSecret) {
        return new ChannelConfig(UuidGenerator.newV7(), tenantId, agentId, WEB_CHAT, turnstileSecret, null,
                ChannelConfigStatus.ACTIVE, Instant.now(), null, null, Objects.requireNonNull(publicKey, "publicKey"));
    }

    /** The channel type of a web chat, as {@code SupportedChannels} names it. */
    public static final String WEB_CHAT = "web_chat";

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

    /**
     * This config with a new webhook secret, by its hash: the old secret stops authenticating.
     *
     * @throws IllegalStateException for an app-routed config, which has no secret of its own
     */
    public ChannelConfig withWebhookSecretHash(String newWebhookSecretHash) {
        if (isAppRouted()) {
            throw new IllegalStateException("an app-routed config has no webhook secret of its own");
        }
        if (isPublicKeyRouted()) {
            throw new IllegalStateException("a public-key-routed config has no webhook secret");
        }
        return new ChannelConfig(id, tenantId, agentId, channelType, credential, newWebhookSecretHash, status,
                createdAt, null, null, null);
    }

    /** Whether browsers reach this config by its public key (a web chat) rather than a webhook. */
    public boolean isPublicKeyRouted() {
        return publicKey != null;
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
