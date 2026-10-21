package dev.cauce.memory.message;

import dev.cauce.core.message.MessageRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link MessageEntity}. Derived queries only for now.
 * Result sets are filtered by the messages Row-Level Security policy according to the
 * active tenant context.
 */
public interface MessageRepository extends JpaRepository<MessageEntity, UUID> {

    /**
     * Messages of a conversation in chronological order (oldest first). Unbounded: used by
     * context assembly ({@code ConversationGateway}), which needs the full thread.
     */
    List<MessageEntity> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    /**
     * First keyset page of a conversation's messages, ordered by id. UUIDv7 ids are a strict
     * total order (insertion order per JVM), and Postgres compares {@code uuid} bytewise
     * unsigned — never replicate this comparison in Java ({@code UUID.compareTo} is signed).
     */
    List<MessageEntity> findByConversationIdOrderByIdAsc(UUID conversationId, Limit limit);

    /** Keyset page after the cursor: messages with {@code id > afterId}, ordered by id. */
    List<MessageEntity> findByConversationIdAndIdGreaterThanOrderByIdAsc(
            UUID conversationId, UUID afterId, Limit limit);

    /**
     * First keyset page of a conversation's messages without one role, ordered by id. The public
     * messages listing passes {@code SYSTEM} (ADR 0005, C3); filtering in the query keeps every
     * page full and the cursor exact.
     */
    List<MessageEntity> findByConversationIdAndRoleNotOrderByIdAsc(
            UUID conversationId, MessageRole excludedRole, Limit limit);

    /** Keyset page after the cursor, without one role; see the first-page variant. */
    List<MessageEntity> findByConversationIdAndIdGreaterThanAndRoleNotOrderByIdAsc(
            UUID conversationId, UUID afterId, MessageRole excludedRole, Limit limit);

    /**
     * Finds a message by id, scoped to a conversation. Used to validate that a referenced
     * message both exists and belongs to the expected conversation before acting on it.
     */
    Optional<MessageEntity> findByIdAndConversationId(UUID id, UUID conversationId);
}
