package dev.cauce.memory.message;

import java.util.List;
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
}
