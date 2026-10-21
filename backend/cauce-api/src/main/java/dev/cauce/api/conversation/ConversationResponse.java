package dev.cauce.api.conversation;

import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.conversation.ConversationStatus;
import dev.cauce.core.identity.Identity;
import dev.cauce.core.identity.IdentityKind;
import java.time.Instant;
import java.util.UUID;

/**
 * API representation of a {@link Conversation}. Decoupled from the domain type so the wire
 * contract can evolve independently. Serialised in snake_case (global Jackson naming strategy);
 * the lifecycle timestamps are null unless the conversation reached the matching state.
 *
 * <p>The interlocutor is nested as its identity (ADR 0004): who the thread is with, including
 * the channel the identity belongs to. There is no top-level {@code channel_type}: the
 * conversation does not carry one (PC-2). {@code channel_config_id} is the channel instance
 * the thread came in through, null for the {@code api} channel. The identity's value is
 * returned here — RLS limits it to tenants that may see the conversation; the opaque-ids-only
 * rule of PC-3 constrains the audit chain, not the API.
 */
public record ConversationResponse(
        UUID id,
        UUID agentId,
        IdentityResponse identity,
        UUID channelConfigId,
        ConversationStatus status,
        Instant startedAt,
        Instant lastMessageAt,
        Instant closedAt,
        Instant escalatedAt,
        Instant archivedAt) {

    /** The conversation's interlocutor. */
    public record IdentityResponse(UUID id, String channelType, IdentityKind kind, String value) {

        static IdentityResponse from(Identity identity) {
            return new IdentityResponse(identity.id(), identity.channelType(), identity.kind(), identity.value());
        }
    }

    /** @param identity the conversation's identity ({@code conversation.identityId()}) */
    public static ConversationResponse from(Conversation conversation, Identity identity) {
        if (!identity.id().equals(conversation.identityId())) {
            throw new IllegalArgumentException("Identity " + identity.id() + " is not the interlocutor of conversation "
                    + conversation.id());
        }
        return new ConversationResponse(
                conversation.id(),
                conversation.agentId(),
                IdentityResponse.from(identity),
                conversation.channelConfigId().orElse(null),
                conversation.status(),
                conversation.startedAt(),
                conversation.lastMessageAt(),
                conversation.closedAt(),
                conversation.escalatedAt(),
                conversation.archivedAt());
    }
}
