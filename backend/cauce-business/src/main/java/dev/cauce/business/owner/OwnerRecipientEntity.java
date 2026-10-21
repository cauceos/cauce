package dev.cauce.business.owner;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping of an {@code owner_recipients} row (V37). */
@Entity
@Table(name = "owner_recipients")
public class OwnerRecipientEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "channel_config_id", nullable = false)
    private UUID channelConfigId;

    @Column(name = "identity_id", nullable = false)
    private UUID identityId;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    protected OwnerRecipientEntity() {
        // for JPA
    }

    public OwnerRecipientEntity(UUID id, UUID tenantId, UUID channelConfigId, UUID identityId, Instant linkedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.channelConfigId = channelConfigId;
        this.identityId = identityId;
        this.linkedAt = linkedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getChannelConfigId() {
        return channelConfigId;
    }

    public UUID getIdentityId() {
        return identityId;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }
}
