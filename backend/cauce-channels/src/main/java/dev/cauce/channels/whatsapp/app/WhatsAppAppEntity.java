package dev.cauce.channels.whatsapp.app;

import dev.cauce.channels.config.ChannelConfigStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping of a {@code whatsapp_apps} row (V30). {@code appSecret} holds the sealed form. */
@Entity
@Table(name = "whatsapp_apps")
public class WhatsAppAppEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "meta_app_id", nullable = false, length = 64)
    private String metaAppId;

    @Column(name = "app_secret", nullable = false)
    private String appSecret;

    @Column(name = "verify_token_hash", nullable = false, length = 255)
    private String verifyTokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ChannelConfigStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WhatsAppAppEntity() {
        // for JPA
    }

    public WhatsAppAppEntity(UUID id, UUID tenantId, String metaAppId, String appSecret, String verifyTokenHash,
                             ChannelConfigStatus status, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.metaAppId = metaAppId;
        this.appSecret = appSecret;
        this.verifyTokenHash = verifyTokenHash;
        this.status = status;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getMetaAppId() {
        return metaAppId;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public String getVerifyTokenHash() {
        return verifyTokenHash;
    }

    public ChannelConfigStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
