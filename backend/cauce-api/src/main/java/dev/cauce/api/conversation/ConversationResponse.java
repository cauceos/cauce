package dev.cauce.api.conversation;

import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.conversation.ConversationStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * API representation of a {@link Conversation}. Decoupled from the domain type so the wire
 * contract can evolve independently. Serialised in snake_case (global Jackson naming strategy);
 * the lifecycle timestamps are null unless the conversation reached the matching state.
 */
public record ConversationResponse(
        UUID id,
        UUID agentId,
        String channelType,
        String externalIdentityRef,
        ConversationStatus status,
        Instant startedAt,
        Instant lastMessageAt,
        Instant closedAt,
        Instant escalatedAt,
        Instant archivedAt) {

    public static ConversationResponse from(Conversation conversation) {
        return new ConversationResponse(
                conversation.id(),
                conversation.agentId(),
                conversation.channelType(),
                conversation.externalIdentityRef(),
                conversation.status(),
                conversation.startedAt(),
                conversation.lastMessageAt(),
                conversation.closedAt(),
                conversation.escalatedAt(),
                conversation.archivedAt());
    }
}
