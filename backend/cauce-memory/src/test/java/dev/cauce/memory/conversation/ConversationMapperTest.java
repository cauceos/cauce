package dev.cauce.memory.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.conversation.ConversationStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationMapperTest {

    private final ConversationMapper mapper = new ConversationMapper();

    @Test
    void roundTrip_domainToEntityToDomain_preservesAllFields_withNullClosedAt() {
        Conversation original = Conversation.start(UUID.randomUUID(), "whatsapp", "+34612345678");

        Conversation roundTripped = mapper.toDomain(mapper.toEntity(original));

        assertThat(roundTripped.id()).isEqualTo(original.id());
        assertThat(roundTripped.agentId()).isEqualTo(original.agentId());
        assertThat(roundTripped.channelType()).isEqualTo(original.channelType());
        assertThat(roundTripped.externalIdentityRef()).isEqualTo(original.externalIdentityRef());
        assertThat(roundTripped.status()).isEqualTo(ConversationStatus.OPEN);
        assertThat(roundTripped.startedAt()).isEqualTo(original.startedAt());
        assertThat(roundTripped.lastMessageAt()).isEqualTo(original.lastMessageAt());
        assertThat(roundTripped.closedAt()).isNull();
        assertThat(roundTripped.escalatedAt()).isNull();
        assertThat(roundTripped.archivedAt()).isNull();
        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void roundTrip_preservesNonNullClosedAt() {
        Instant now = Instant.now();
        Conversation closed = Conversation.rehydrate(UUID.randomUUID(), UUID.randomUUID(),
                "email", "patient@example.com", ConversationStatus.CLOSED, now, now, now, null, null);

        Conversation roundTripped = mapper.toDomain(mapper.toEntity(closed));

        assertThat(roundTripped.status()).isEqualTo(ConversationStatus.CLOSED);
        assertThat(roundTripped.closedAt()).isEqualTo(now);
    }

    @Test
    void roundTrip_preservesEscalatedAtAndArchivedAt() {
        Instant escalatedAt = Instant.now().minusSeconds(60);
        Instant archivedAt = Instant.now();
        // An archived conversation that had been escalated earlier.
        Conversation archived = Conversation.rehydrate(UUID.randomUUID(), UUID.randomUUID(),
                "voice", "+34600111222", ConversationStatus.ARCHIVED,
                escalatedAt.minusSeconds(60), escalatedAt, null, escalatedAt, archivedAt);

        Conversation roundTripped = mapper.toDomain(mapper.toEntity(archived));

        assertThat(roundTripped.status()).isEqualTo(ConversationStatus.ARCHIVED);
        assertThat(roundTripped.escalatedAt()).isEqualTo(escalatedAt);
        assertThat(roundTripped.archivedAt()).isEqualTo(archivedAt);
        assertThat(roundTripped.closedAt()).isNull();
    }

    @Test
    void toEntity_copiesAllFields() {
        Conversation conversation = Conversation.start(UUID.randomUUID(), "web_chat", "session-abc");

        ConversationEntity entity = mapper.toEntity(conversation);

        assertThat(entity.getId()).isEqualTo(conversation.id());
        assertThat(entity.getAgentId()).isEqualTo(conversation.agentId());
        assertThat(entity.getChannelType()).isEqualTo("web_chat");
        assertThat(entity.getExternalIdentityRef()).isEqualTo("session-abc");
        assertThat(entity.getStatus()).isEqualTo(ConversationStatus.OPEN);
        assertThat(entity.getClosedAt()).isNull();
        assertThat(entity.getEscalatedAt()).isNull();
        assertThat(entity.getArchivedAt()).isNull();
    }
}
