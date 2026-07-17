package dev.cauce.memory.message;

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
     * Finds a message by id, scoped to a conversation. Used to validate that a referenced
     * message both exists and belongs to the expected conversation before acting on it.
     */
    Optional<MessageEntity> findByIdAndConversationId(UUID id, UUID conversationId);
}
