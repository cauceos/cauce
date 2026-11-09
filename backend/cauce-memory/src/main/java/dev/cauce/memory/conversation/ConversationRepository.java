package dev.cauce.memory.conversation;

import dev.cauce.core.conversation.ConversationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link ConversationEntity}. Derived queries only for now.
 * Result sets are filtered by the conversations Row-Level Security policy according to
 * the active tenant context.
 */
public interface ConversationRepository extends JpaRepository<ConversationEntity, UUID> {

    List<ConversationEntity> findByAgentId(UUID agentId);

    List<ConversationEntity> findByAgentIdAndStatus(UUID agentId, ConversationStatus status);

    /**
     * Finds the conversation with the given status for an {@code (agent, identity, channel
     * config)} key; {@code status = OPEN} locates the live thread an inbound message is routed
     * to. A {@code null} {@code channelConfigId} matches only rows whose config is NULL (the
     * {@code api} channel): Spring Data derives {@code IS NULL} for a null argument, which is
     * the same equality the V26 {@code NULLS NOT DISTINCT} index enforces.
     */
    Optional<ConversationEntity> findByAgentIdAndIdentityIdAndChannelConfigIdAndStatus(
            UUID agentId, UUID identityId, UUID channelConfigId, ConversationStatus status);

    /**
     * Inserts a new OPEN conversation for the {@code (agent, identity, channel config)} key
     * unless one already exists, in a single race-free statement. Returns {@code 1} when this
     * call created the row, {@code 0} when a concurrent caller already had — the
     * {@code idx_conversations_one_open_thread} partial unique index (V26, {@code NULLS NOT
     * DISTINCT}, so a NULL api config deduplicates too) is the conflict arbiter. The caller then
     * re-reads the OPEN row to obtain the winner. {@code started_at} and
     * {@code last_message_at} default to {@code now()}. Runs in the caller's transaction
     * with the tenant RLS context applied, so the insert is subject to the conversations
     * {@code WITH CHECK} visibility policy.
     */
    @Modifying
    @Query(value = """
            INSERT INTO conversations (id, agent_id, identity_id, channel_config_id, status)
            VALUES (:id, :agentId, :identityId, CAST(:channelConfigId AS uuid), 'OPEN')
            ON CONFLICT (agent_id, identity_id, channel_config_id) WHERE status = 'OPEN'
            DO NOTHING
            """, nativeQuery = true)
    int insertOpenConversationIfAbsent(@Param("id") UUID id,
                                       @Param("agentId") UUID agentId,
                                       @Param("identityId") UUID identityId,
                                       @Param("channelConfigId") UUID channelConfigId);

    /**
     * Advances {@code lastMessageAt} of a conversation, called when a message is appended.
     * A single bulk UPDATE, so the conversation does not need to be loaded or mutated via
     * a setter; the caller passes the new message's timestamp for conversational
     * consistency. Subject to the conversations RLS policy in the current transaction.
     */
    /**
     * How long an OPEN conversation has gone without a conversational message, and its agent's
     * window (ADR 0013, T2): seconds from its newest USER, AGENT or FALLBACK message (its start, when
     * it has none) to the database's {@code now()}. Tool calls, tool results and SYSTEM records do
     * not count. Empty when the conversation is not OPEN, or not visible (RLS).
     */
    @Query(value = """
            SELECT CAST(EXTRACT(EPOCH FROM (now() - COALESCE(
                       (SELECT max(m.created_at) FROM messages m
                         WHERE m.conversation_id = c.id AND m.role IN ('USER', 'AGENT', 'FALLBACK')),
                       c.started_at))) AS bigint) AS "idleSeconds",
                   a.new_thread_after_hours AS "windowHours"
              FROM conversations c JOIN agents a ON a.id = c.agent_id
             WHERE c.id = :conversationId AND c.status = 'OPEN'
            """, nativeQuery = true)
    Optional<Inactivity> findInactivity(@Param("conversationId") UUID conversationId);

    /** What {@link #findInactivity} reads. */
    interface Inactivity {
        long getIdleSeconds();

        int getWindowHours();
    }

    /**
     * Closes the conversation if it is still OPEN, stamping {@code closed_at} with the database's
     * {@code now()} (ADR 0008). Returns 1 when this call closed it, 0 when it was no longer OPEN (a
     * concurrent message closed it first). Subject to the conversations RLS policy.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE conversations SET status = 'CLOSED', closed_at = now() "
            + "WHERE id = :conversationId AND status = 'OPEN'", nativeQuery = true)
    int closeIfOpen(@Param("conversationId") UUID conversationId);

    @Modifying
    @Query("update ConversationEntity c set c.lastMessageAt = :timestamp where c.id = :conversationId")
    int touchLastMessageAt(@Param("conversationId") UUID conversationId,
                           @Param("timestamp") Instant timestamp);
}
