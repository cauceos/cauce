package dev.cauce.memory.message;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link MessageEntity}. Derived queries only for now.
 * Result sets are filtered by the messages Row-Level Security policy according to the
 * active tenant context.
 */
public interface MessageRepository extends JpaRepository<MessageEntity, UUID> {

    /** Messages of a conversation in chronological order (oldest first). */
    List<MessageEntity> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    /**
     * Finds a message by id, scoped to a conversation. Used to validate that a referenced
     * message both exists and belongs to the expected conversation before acting on it.
     */
    Optional<MessageEntity> findByIdAndConversationId(UUID id, UUID conversationId);
}
