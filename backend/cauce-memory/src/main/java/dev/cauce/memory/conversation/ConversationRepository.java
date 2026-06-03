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
     * Finds a single conversation for a given (agent, channel, external user, status)
     * tuple. Used to locate the active (OPEN) conversation when routing an incoming
     * message to the right thread.
     */
    Optional<ConversationEntity> findByAgentIdAndChannelTypeAndExternalIdentityRefAndStatus(
            UUID agentId, String channelType, String externalIdentityRef, ConversationStatus status);

    /**
     * Inserts a new OPEN conversation for the {@code (agent, channel, external user)} tuple
     * unless one already exists, in a single race-free statement. Returns {@code 1} when this
     * call created the row, {@code 0} when a concurrent caller already had — the
     * {@code idx_conversations_one_open_per_identity} partial unique index (V13) is the conflict
     * arbiter. The caller then re-reads the OPEN row to obtain the winner. {@code started_at}
     * and {@code last_message_at} default to {@code now()}; the conversation starts OPEN with no
     * lifecycle timestamps. Runs in the caller's transaction with the tenant RLS context applied,
     * so the insert is subject to the conversations {@code WITH CHECK} visibility policy.
     */
    @Modifying
    @Query(value = """
            INSERT INTO conversations (id, agent_id, channel_type, external_identity_ref, status)
            VALUES (:id, :agentId, :channelType, :externalIdentityRef, 'OPEN')
            ON CONFLICT (agent_id, channel_type, external_identity_ref) WHERE status = 'OPEN'
            DO NOTHING
            """, nativeQuery = true)
    int insertOpenConversationIfAbsent(@Param("id") UUID id,
                                       @Param("agentId") UUID agentId,
                                       @Param("channelType") String channelType,
                                       @Param("externalIdentityRef") String externalIdentityRef);

    /**
     * Advances {@code lastMessageAt} of a conversation, called when a message is appended.
     * A single bulk UPDATE, so the conversation does not need to be loaded or mutated via
     * a setter; the caller passes the new message's timestamp for conversational
     * consistency. Subject to the conversations RLS policy in the current transaction.
     */
    @Modifying
    @Query("update ConversationEntity c set c.lastMessageAt = :timestamp where c.id = :conversationId")
    int touchLastMessageAt(@Param("conversationId") UUID conversationId,
                           @Param("timestamp") Instant timestamp);
}
