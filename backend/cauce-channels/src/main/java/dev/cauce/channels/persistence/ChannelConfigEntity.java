package dev.cauce.channels.persistence;

import dev.cauce.channels.config.ChannelConfigStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA persistence mapping for a channel config row. Infrastructure detail of
 * cauce-channels; the domain type is {@link dev.cauce.channels.config.ChannelConfig},
 * converted by {@link ChannelConfigMapper}.
 */
@Entity
@Table(name = "channel_configs")
public class ChannelConfigEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "agent_id", nullable = false, updatable = false)
    private UUID agentId;

    @Column(name = "channel_type", nullable = false, updatable = false, length = 20)
    private String channelType;

    @Column(name = "credential", nullable = false, length = 512)
    private String credential;

    @Column(name = "webhook_secret_hash", nullable = false, length = 255)
    private String webhookSecretHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ChannelConfigStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ChannelConfigEntity() {
        // for JPA
    }

    public ChannelConfigEntity(UUID id, UUID tenantId, UUID agentId, String channelType,
                               String credential, String webhookSecretHash,
                               ChannelConfigStatus status, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.agentId = agentId;
        this.channelType = channelType;
        this.credential = credential;
        this.webhookSecretHash = webhookSecretHash;
        this.status = status;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getChannelType() {
        return channelType;
    }

    public String getCredential() {
        return credential;
    }

    public String getWebhookSecretHash() {
        return webhookSecretHash;
    }

    public ChannelConfigStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
