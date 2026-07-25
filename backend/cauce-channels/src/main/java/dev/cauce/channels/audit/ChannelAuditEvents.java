package dev.cauce.channels.audit;

import dev.cauce.channels.config.ChannelConfig;
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
}
