package dev.cauce.memory.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.conversation.ConversationStatus;
import dev.cauce.core.identity.Identity;
import dev.cauce.core.identity.IdentityKind;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationMapperTest {

    private final ConversationMapper mapper = new ConversationMapper();

    @Test
    void roundTrip_domainToEntityToDomain_preservesAllFields_withNullClosedAt() {
        Conversation original = Conversation.start(UUID.randomUUID(),
                identity("whatsapp", IdentityKind.PHONE_NUMBER, "+34612345678"), UUID.randomUUID());

        Conversation roundTripped = mapper.toDomain(mapper.toEntity(original));

        assertThat(roundTripped.id()).isEqualTo(original.id());
        assertThat(roundTripped.agentId()).isEqualTo(original.agentId());
        assertThat(roundTripped.identityId()).isEqualTo(original.identityId());
        assertThat(roundTripped.channelConfigId()).isEqualTo(original.channelConfigId());
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
                UUID.randomUUID(), UUID.randomUUID(), ConversationStatus.CLOSED, now, now, now, null, null);

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
                UUID.randomUUID(), UUID.randomUUID(), ConversationStatus.ARCHIVED,
                escalatedAt.minusSeconds(60), escalatedAt, null, escalatedAt, archivedAt);

        Conversation roundTripped = mapper.toDomain(mapper.toEntity(archived));

        assertThat(roundTripped.status()).isEqualTo(ConversationStatus.ARCHIVED);
        assertThat(roundTripped.escalatedAt()).isEqualTo(escalatedAt);
        assertThat(roundTripped.archivedAt()).isEqualTo(archivedAt);
        assertThat(roundTripped.closedAt()).isNull();
    }

    @Test
    void toEntity_copiesAllFields() {
        Conversation conversation = Conversation.start(UUID.randomUUID(),
                identity("api", IdentityKind.CLIENT_REFERENCE, "session-abc"), null);

        ConversationEntity entity = mapper.toEntity(conversation);

        assertThat(entity.getId()).isEqualTo(conversation.id());
        assertThat(entity.getAgentId()).isEqualTo(conversation.agentId());
        assertThat(entity.getIdentityId()).isEqualTo(conversation.identityId());
        assertThat(entity.getChannelConfigId()).isNull();
        assertThat(entity.getStatus()).isEqualTo(ConversationStatus.OPEN);
        assertThat(entity.getClosedAt()).isNull();
        assertThat(entity.getEscalatedAt()).isNull();
        assertThat(entity.getArchivedAt()).isNull();
    }

    private static Identity identity(String channelType, IdentityKind kind, String value) {
        return Identity.create(UUID.randomUUID(), channelType, kind, value);
    }
}
