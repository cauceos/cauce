package dev.cauce.memory.message;

import dev.cauce.core.message.MessageRole;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link MessageEntity}. A conversation is always read in {@code seq}
 * order, never by {@code created_at}, which is a time for the windows that compare times. Result sets are filtered by the messages Row-Level Security policy according to the
 * active tenant context.
 */
public interface MessageRepository extends JpaRepository<MessageEntity, UUID> {

    /**
     * Messages of a conversation in order, oldest first, by {@code seq}: the position the
     * database assigned on insert, never a clock (V44). Unbounded: used by context assembly
     * ({@code ConversationGateway}), which needs the full thread.
     */
    List<MessageEntity> findByConversationIdOrderBySeqAsc(UUID conversationId);

    /**
     * First keyset page of a conversation's messages without one role, in conversation order. The
     * public messages listing passes {@code SYSTEM} (ADR 0005, C3); filtering in the query keeps
     * every page full and the cursor exact.
     */
    List<MessageEntity> findByConversationIdAndRoleNotOrderBySeqAsc(
            UUID conversationId, MessageRole excludedRole, Limit limit);

    /**
     * Keyset page after the cursor, without one role: the messages after {@code afterId} in
     * conversation order. The cursor still names a message; its {@code seq} is the key. A cursor
     * from another conversation yields an empty page.
     */
    @Query("SELECT m FROM MessageEntity m WHERE m.conversationId = :conversationId AND m.role <> :excludedRole "
            + "AND m.seq > (SELECT a.seq FROM MessageEntity a WHERE a.id = :afterId "
            + "AND a.conversationId = :conversationId) ORDER BY m.seq")
    List<MessageEntity> findPageAfter(@Param("conversationId") UUID conversationId, @Param("afterId") UUID afterId,
                                      @Param("excludedRole") MessageRole excludedRole, Limit limit);

    /**
     * Finds a message by id, scoped to a conversation. Used to validate that a referenced
     * message both exists and belongs to the expected conversation before acting on it.
     */
    Optional<MessageEntity> findByIdAndConversationId(UUID id, UUID conversationId);

    /** The newest message of one role in a conversation (e.g. the interlocutor's last USER message). */
    Optional<MessageEntity> findFirstByConversationIdAndRoleOrderBySeqDesc(UUID conversationId, MessageRole role);

    /**
     * When {@code identityId} last wrote through channel instance {@code channelConfigId}, across
     * its conversations there: what a channel with a reply window measures a proactive message
     * against (ADR 0007, B9). Empty when it never wrote there.
     */
    @Query("SELECT max(m.createdAt) FROM MessageEntity m WHERE m.role = dev.cauce.core.message.MessageRole.USER "
            + "AND m.conversationId IN (SELECT c.id FROM ConversationEntity c "
            + "WHERE c.identityId = :identityId AND c.channelConfigId = :channelConfigId)")
    Optional<Instant> findLastUserMessageAt(@Param("identityId") UUID identityId,
                                            @Param("channelConfigId") UUID channelConfigId);
}
