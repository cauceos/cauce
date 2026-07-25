package dev.cauce.channels.audit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.core.audit.AuditEvent;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChannelAuditEventsTest {

    @Test
    void channelConfigured_buildsPayloadWithoutCredentialOrSecretHash() {
        UUID tenantId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID actorTenantId = UUID.randomUUID();
        ChannelConfig config = ChannelConfig.create(tenantId, agentId, "telegram",
                "12345:bot-token", "secret-hash-value");

        AuditEvent event = ChannelAuditEvents.channelConfigured(config, actorTenantId);

        assertThat(event.tenantId()).isEqualTo(tenantId); // the SUBJECT's chain
        assertThat(event.eventType()).isEqualTo("admin.channel.configured");
        assertThat(event.payload())
                .containsOnlyKeys("channel_config_id", "agent_id", "tenant_id", "channel_type",
                        "actor_tenant_id")
                .containsEntry("channel_config_id", config.id().toString())
                .containsEntry("agent_id", agentId.toString())
                .containsEntry("channel_type", "telegram")
                .containsEntry("actor_tenant_id", actorTenantId.toString());
        assertThat(event.payload().values())
                .doesNotContain("12345:bot-token", "secret-hash-value");
    }
}
