package dev.cauce.memory.message;

import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageParts;
import org.springframework.stereotype.Component;

/**
 * Hand-written bidirectional mapping between the domain {@link Message} and its JPA
 * {@link MessageEntity}. No external mapping library is used.
 *
 * <p>The parts go to and come from the jsonb {@code parts} column in their one JSON form,
 * {@link MessageParts}: the same form the content hash v2 is computed over. Hibernate handles the
 * list ⇄ jsonb serialization.
 */
@Component
public final class MessageMapper {

    public MessageEntity toEntity(Message message) {
        return new MessageEntity(
                message.id(),
                message.conversationId(),
                message.role(),
                MessageParts.toJson(message.parts()),
                message.failureType().orElse(null),
                message.createdAt());
    }

    public Message toDomain(MessageEntity entity) {
        return Message.rehydrateParts(
                entity.getId(),
                entity.getConversationId(),
                entity.getRole(),
                MessageParts.fromJson(entity.getParts()),
                entity.getFailureType(),
                entity.getCreatedAt());
    }
}
