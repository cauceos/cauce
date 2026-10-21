package dev.cauce.business.owner;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping of an {@code owner_link_codes} row (V37). Only the code's hash is stored. */
@Entity
@Table(name = "owner_link_codes")
public class OwnerLinkCodeEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "channel_config_id", nullable = false, updatable = false)
    private UUID channelConfigId;

    @Column(name = "code_hash", nullable = false, updatable = false, length = 64)
    private String codeHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "used_by_identity_id")
    private UUID usedByIdentityId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OwnerLinkCodeEntity() {
        // for JPA
    }

    public OwnerLinkCodeEntity(UUID id, UUID tenantId, UUID channelConfigId, String codeHash, Instant expiresAt,
                               Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.channelConfigId = channelConfigId;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    /** Marks the code used by {@code identityId} at {@code at}. */
    public void markUsed(UUID identityId, Instant at) {
        this.usedByIdentityId = identityId;
        this.usedAt = at;
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

    public String getCodeHash() {
        return codeHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public UUID getUsedByIdentityId() {
        return usedByIdentityId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
