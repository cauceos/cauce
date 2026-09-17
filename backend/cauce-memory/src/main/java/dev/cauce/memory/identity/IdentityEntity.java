package dev.cauce.memory.identity;

import dev.cauce.core.identity.IdentityKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA persistence mapping for an identity row (V25). Infrastructure detail of cauce-memory;
 * the domain type is {@link dev.cauce.core.identity.Identity}, converted by
 * {@link IdentityMapper}. Every column is immutable after insert: an identity is a fact
 * about who was seen on a channel, never edited.
 */
@Entity
@Table(name = "identities")
public class IdentityEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "channel_type", nullable = false, updatable = false, length = 20)
    private String channelType;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 20)
    private IdentityKind kind;

    @Column(name = "value", nullable = false, updatable = false, length = 320)
    private String value;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IdentityEntity() {
        // for JPA
    }

    public IdentityEntity(UUID id, UUID tenantId, String channelType, IdentityKind kind, String value,
                          Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.channelType = channelType;
        this.kind = kind;
        this.value = value;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getChannelType() {
        return channelType;
    }

    public IdentityKind getKind() {
        return kind;
    }

    public String getValue() {
        return value;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
