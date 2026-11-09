package dev.cauce.channels.webchat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping of {@code web_sessions} (V60); RLS-filtered by its tenant. */
@Entity
@Table(name = "web_sessions")
public class WebSessionEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "channel_config_id", nullable = false, updatable = false)
    private UUID channelConfigId;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consented_at", updatable = false)
    private Instant consentedAt;

    @Column(name = "consent_notice_hash", updatable = false, length = 64)
    private String consentNoticeHash;

    protected WebSessionEntity() {
        // for JPA
    }

    public WebSessionEntity(UUID id, UUID tenantId, UUID channelConfigId, String tokenHash, Instant expiresAt,
                            Instant consentedAt, String consentNoticeHash) {
        this.id = id;
        this.tenantId = tenantId;
        this.channelConfigId = channelConfigId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.consentedAt = consentedAt;
        this.consentNoticeHash = consentNoticeHash;
    }

    public WebSession toDomain() {
        return new WebSession(id, tenantId, channelConfigId, expiresAt, consentedAt);
    }

    public UUID getId() {
        return id;
    }
}
