package dev.cauce.channels.audit;

import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.whatsapp.app.WhatsAppApp;
import dev.cauce.core.audit.AuditEvent;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The Family-B auditable vocabulary owned by channels: a channel instance was bound to an
 * agent. The constant lives with its emitter (cauce-channels never depends on
 * cauce-tenancy), mirroring the tenancy/orchestration event factories. The event lands in
 * the chain of the SUBJECT (the config's owning tenant) with the acting tenant as
 * {@code actor_tenant_id}.
 *
 * <p>Never in the payload: the provider {@code credential} (e.g. the Telegram bot token)
 * or the webhook secret — neither its plaintext (returned exactly once) nor its stored
 * hash. Only ids, the channel type, and the actor.
 */
public final class ChannelAuditEvents {

    public static final String CHANNEL_CONFIGURED = "admin.channel.configured";
    public static final String WHATSAPP_APP_REGISTERED = "admin.whatsapp_app.registered";
    public static final String WEBHOOK_SECRET_ROTATED = "admin.channel.webhook_secret_rotated";
    public static final String UNSUPPORTED_MESSAGE = "conduct.message.unsupported";

    private ChannelAuditEvents() {
    }

    /** A channel instance was bound to an agent — recorded in the owning tenant's chain. */
    public static AuditEvent channelConfigured(ChannelConfig config, UUID actorTenantId) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(actorTenantId, "actorTenantId must not be null");
        return new AuditEvent(config.tenantId(), CHANNEL_CONFIGURED, Map.of(
                "channel_config_id", config.id().toString(),
                "agent_id", config.agentId().toString(),
                "tenant_id", config.tenantId().toString(),
                "channel_type", config.channelType(),
                "actor_tenant_id", actorTenantId.toString()));
    }

    /**
     * The webhook secret of a channel instance was replaced — recorded in the owning tenant's chain:
     * ids only, never the new secret or its hash.
     */
    public static AuditEvent webhookSecretRotated(ChannelConfig config, UUID actorTenantId) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(actorTenantId, "actorTenantId must not be null");
        return new AuditEvent(config.tenantId(), WEBHOOK_SECRET_ROTATED, Map.of(
                "channel_config_id", config.id().toString(),
                "agent_id", config.agentId().toString(),
                "tenant_id", config.tenantId().toString(),
                "channel_type", config.channelType(),
                "actor_tenant_id", actorTenantId.toString()));
    }

    /**
     * A WhatsApp app was registered (ADR 0006, W2) — recorded in the owning tenant's chain: ids
     * and Meta's app id only, never the app secret, the verify token or its hash.
     */
    public static AuditEvent whatsAppAppRegistered(WhatsAppApp app, UUID actorTenantId) {
        Objects.requireNonNull(app, "app must not be null");
        Objects.requireNonNull(actorTenantId, "actorTenantId must not be null");
        return new AuditEvent(app.tenantId(), WHATSAPP_APP_REGISTERED, Map.of(
                "whatsapp_app_id", app.id().toString(),
                "tenant_id", app.tenantId().toString(),
                "meta_app_id", app.metaAppId(),
                "actor_tenant_id", actorTenantId.toString()));
    }

    /**
     * An inbound message of a type Cauce does not read yet arrived and was answered with the
     * fixed text-only reply (ADR 0006, W5). Recorded in the number's tenant chain: the message
     * type and ids only — never its content, its media or the sender's identifier.
     */
    public static AuditEvent unsupportedMessage(ChannelConfig number, String messageType, String providerMessageId) {
        Objects.requireNonNull(number, "number must not be null");
        Objects.requireNonNull(messageType, "messageType must not be null");
        return new AuditEvent(number.tenantId(), UNSUPPORTED_MESSAGE, Map.of(
                "channel_config_id", number.id().toString(),
                "agent_id", number.agentId().toString(),
                "channel_type", number.channelType(),
                "message_type", messageType,
                "provider_message_id", providerMessageId == null ? "" : providerMessageId));
    }
}
