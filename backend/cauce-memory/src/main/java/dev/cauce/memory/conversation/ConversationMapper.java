package dev.cauce.memory.conversation;

import dev.cauce.core.conversation.Conversation;
import org.springframework.stereotype.Component;

/**
 * Hand-written bidirectional mapping between the domain {@link Conversation} and its
 * JPA {@link ConversationEntity}. No external mapping library is used.
 */
@Component
public final class ConversationMapper {

    public ConversationEntity toEntity(Conversation conversation) {
        return new ConversationEntity(
                conversation.id(),
                conversation.agentId(),
                conversation.channelType(),
                conversation.externalIdentityRef(),
                conversation.status(),
                conversation.startedAt(),
                conversation.lastMessageAt(),
                conversation.closedAt());
    }

    public Conversation toDomain(ConversationEntity entity) {
        return Conversation.rehydrate(
                entity.getId(),
                entity.getAgentId(),
                entity.getChannelType(),
                entity.getExternalIdentityRef(),
                entity.getStatus(),
                entity.getStartedAt(),
                entity.getLastMessageAt(),
                entity.getClosedAt());
    }
}
