package dev.cauce.channels.webchat;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@code web_events} and {@code web_opening_counts} (V66), RLS-filtered. Every
 * comparison against "now" runs in Postgres (ADR 0008).
 */
public interface WebEventRepository extends JpaRepository<WebEventEntity, UUID> {

    /** Records a purchase once per channel and order; 0 when the order was reported already. */
    @Modifying
    @Query(value = "INSERT INTO web_events (id, tenant_id, channel_config_id, session_id, kind, variant, order_id, value, "
            + "attributed) VALUES (:id, :tenantId, :configId, :sessionId, 'PURCHASE', :variant, :orderId, :value, "
            + ":attributed) ON CONFLICT (channel_config_id, order_id) WHERE kind = 'PURCHASE' DO NOTHING",
            nativeQuery = true)
    int insertPurchase(@Param("id") UUID id, @Param("tenantId") UUID tenantId, @Param("configId") UUID configId,
                       @Param("sessionId") UUID sessionId, @Param("variant") String variant,
                       @Param("orderId") String orderId, @Param("value") BigDecimal value,
                       @Param("attributed") boolean attributed);

    /**
     * Whether the session wrote, or was answered, on the channel within the last 7 days: what attributes a
     * purchase to the chat (ADR 0014, WC8), by the database clock.
     */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM messages m JOIN conversations c ON c.id = m.conversation_id "
            + "JOIN identities i ON i.id = c.identity_id WHERE i.channel_type = 'web_chat' AND i.kind = 'SESSION_ID' "
            + "AND i.value = :sessionId AND c.channel_config_id = :configId AND m.role IN ('USER', 'AGENT') "
            + "AND m.created_at >= now() - interval '7 days')", nativeQuery = true)
    boolean sessionTalkedWithinAttributionWindow(@Param("sessionId") String sessionId, @Param("configId") UUID configId);

    /** Counts one opening that ended without a message, in its variant's aggregate for today (UTC). */
    @Modifying
    @Query(value = "INSERT INTO web_opening_counts (tenant_id, channel_config_id, day, variant, kind, signal, openings) "
            + "VALUES (:tenantId, :configId, (now() AT TIME ZONE 'UTC')::date, :variant, :kind, :signal, 1) "
            + "ON CONFLICT (channel_config_id, day, variant, kind, signal) "
            + "DO UPDATE SET openings = web_opening_counts.openings + 1", nativeQuery = true)
    int countOpening(@Param("tenantId") UUID tenantId, @Param("configId") UUID configId,
                     @Param("variant") String variant, @Param("kind") String kind, @Param("signal") String signal);
}
