package dev.cauce.channels.receipt;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Receipts of inbound provider messages handled without an ingest (V32). */
public interface InboundReceiptRepository extends JpaRepository<InboundReceiptEntity, UUID> {

    /**
     * Claims {@code (channelConfigId, providerMessageId)} in one race-free statement: {@code 1}
     * when this call is the first to handle the message, {@code 0} when it was handled already. A
     * concurrent claim blocks on the unique constraint until the first transaction commits, then
     * returns {@code 0}. Runs in the caller's transaction under RLS (the insert must pass the
     * receipts policy's {@code WITH CHECK} for {@code tenantId}).
     */
    @Modifying
    @Query(value = """
            INSERT INTO channel_inbound_receipts (id, tenant_id, channel_config_id, provider_message_id)
            VALUES (:id, :tenantId, :channelConfigId, :providerMessageId)
            ON CONFLICT ON CONSTRAINT channel_inbound_receipts_key DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("tenantId") UUID tenantId,
                       @Param("channelConfigId") UUID channelConfigId,
                       @Param("providerMessageId") String providerMessageId);
}
