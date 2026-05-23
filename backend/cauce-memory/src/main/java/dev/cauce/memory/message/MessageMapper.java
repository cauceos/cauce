package dev.cauce.memory.message;

import dev.cauce.core.message.Message;
import org.springframework.stereotype.Component;

/**
 * Hand-written bidirectional mapping between the domain {@link Message} and its JPA
 * {@link MessageEntity}. No external mapping library is used.
 */
@Component
public final class MessageMapper {

    public MessageEntity toEntity(Message message) {
        return new MessageEntity(
                message.id(),
                message.conversationId(),
                message.role(),
                message.content(),
                message.createdAt());
    }

    public Message toDomain(MessageEntity entity) {
        return Message.rehydrate(
                entity.getId(),
                entity.getConversationId(),
                entity.getRole(),
                entity.getContent(),
                entity.getCreatedAt());
    }
}
