package dev.cauce.channels.receipt;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A {@code channel_inbound_receipts} row (V32): the claim that a provider message acknowledged
 * without being ingested has been handled once. Written only through
 * {@link InboundReceiptRepository#insertIfAbsent}.
 */
@Entity
@Table(name = "channel_inbound_receipts")
public class InboundReceiptEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "channel_config_id", nullable = false, updatable = false)
    private UUID channelConfigId;

    @Column(name = "provider_message_id", nullable = false, updatable = false, length = 255)
    private String providerMessageId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected InboundReceiptEntity() {
        // for JPA
    }

    public UUID getId() {
        return id;
    }

    public UUID getChannelConfigId() {
        return channelConfigId;
    }

    public String getProviderMessageId() {
        return providerMessageId;
    }
}
