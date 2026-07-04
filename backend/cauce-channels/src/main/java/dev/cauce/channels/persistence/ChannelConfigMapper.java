package dev.cauce.channels.persistence;

import dev.cauce.channels.config.ChannelConfig;
import org.springframework.stereotype.Component;

/**
 * Hand-written bidirectional mapping between the domain {@link ChannelConfig} and its JPA
 * {@link ChannelConfigEntity}. No external mapping library is used.
 */
@Component
public final class ChannelConfigMapper {

    public ChannelConfigEntity toEntity(ChannelConfig config) {
        return new ChannelConfigEntity(
                config.id(),
                config.tenantId(),
                config.agentId(),
                config.channelType(),
                config.credential(),
                config.webhookSecretHash(),
                config.status(),
                config.createdAt());
    }

    public ChannelConfig toDomain(ChannelConfigEntity entity) {
        return new ChannelConfig(
                entity.getId(),
                entity.getTenantId(),
                entity.getAgentId(),
                entity.getChannelType(),
                entity.getCredential(),
                entity.getWebhookSecretHash(),
                entity.getStatus(),
                entity.getCreatedAt());
    }
}
