package dev.cauce.core.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.identity.Identity;
import dev.cauce.core.identity.IdentityKind;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final Identity IDENTITY =
            Identity.create(UUID.randomUUID(), "whatsapp", IdentityKind.PHONE_NUMBER, "+34612345678");

    @Test
    void start_generatesUuidV7() {
        assertThat(newConversation().id().version()).isEqualTo(7);
    }

    @Test
    void start_assignsOpenStatus() {
        assertThat(newConversation().status()).isEqualTo(ConversationStatus.OPEN);
    }

    @Test
    void start_setsStartedAtAndLastMessageAtToTheSameInstant() {
        Conversation conversation = newConversation();

        assertThat(conversation.startedAt()).isNotNull();
        assertThat(conversation.lastMessageAt()).isEqualTo(conversation.startedAt());
    }

    @Test
    void start_leavesClosedAtNull() {
        assertThat(newConversation().closedAt()).isNull();
    }

    @Test
    void start_assignsFieldsFromTheIdentityAndTheConfig() {
        UUID configId = UUID.randomUUID();
        Conversation conversation = Conversation.start(AGENT, IDENTITY, configId);

        assertThat(conversation.agentId()).isEqualTo(AGENT);
        assertThat(conversation.identityId()).isEqualTo(IDENTITY.id());
        assertThat(conversation.channelConfigId()).contains(configId);
        assertThat(conversation.channelType()).isEqualTo("whatsapp");
        assertThat(conversation.externalIdentityRef()).isEqualTo("+34612345678");
    }

    @Test
    void start_withoutConfig_leavesChannelConfigIdEmpty() {
        assertThat(Conversation.start(AGENT, IDENTITY, null).channelConfigId()).isEmpty();
    }

    @Test
    void start_rejectsNullAgentId() {
        assertThatThrownBy(() -> Conversation.start(null, IDENTITY, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void start_rejectsNullIdentity() {
        assertThatThrownBy(() -> Conversation.start(AGENT, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rehydrate_rejectsNullIdentityId() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> Conversation.rehydrate(UUID.randomUUID(), AGENT, null, null,
                "whatsapp", "+34612345678", ConversationStatus.OPEN, now, now, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void equalsAndHashCode_areBasedOnId() {
        Conversation a = newConversation();
        Conversation sameId = Conversation.rehydrate(a.id(), UUID.randomUUID(), UUID.randomUUID(),
                null, "voice", "+34999",
                ConversationStatus.CLOSED, a.startedAt(), a.lastMessageAt(), a.startedAt(), null, null);
        Conversation different = newConversation();

        assertThat(a).isEqualTo(sameId);
        assertThat(a).hasSameHashCodeAs(sameId);
        assertThat(a).isNotEqualTo(different);
    }

    // --- lifecycle transitions ---

    @Test
    void close_fromOpen_returnsClosedWithClosedAt() {
        Conversation closed = conversationIn(ConversationStatus.OPEN).close();

        assertThat(closed.status()).isEqualTo(ConversationStatus.CLOSED);
        assertThat(closed.closedAt()).isNotNull();
    }

    @Test
    void close_fromEscalated_returnsClosed_preservingEscalatedAt() {
        Conversation escalated = conversationIn(ConversationStatus.OPEN).escalate();

        Conversation closed = escalated.close();

        assertThat(closed.status()).isEqualTo(ConversationStatus.CLOSED);
        assertThat(closed.closedAt()).isNotNull();
        assertThat(closed.escalatedAt()).isEqualTo(escalated.escalatedAt());
    }

    @Test
    void close_fromClosed_throws() {
        assertThatThrownBy(() -> conversationIn(ConversationStatus.CLOSED).close())
                .isInstanceOf(InvalidConversationTransitionException.class)
                .hasMessageContaining("Cannot transition Conversation");
    }

    @Test
    void close_fromArchived_throws() {
        assertThatThrownBy(() -> conversationIn(ConversationStatus.ARCHIVED).close())
                .isInstanceOf(InvalidConversationTransitionException.class);
    }

    @Test
    void escalate_fromOpen_returnsEscalatedWithEscalatedAt() {
        Conversation escalated = conversationIn(ConversationStatus.OPEN).escalate();

        assertThat(escalated.status()).isEqualTo(ConversationStatus.ESCALATED);
        assertThat(escalated.escalatedAt()).isNotNull();
        assertThat(escalated.closedAt()).isNull();
    }

    @Test
    void escalate_fromClosed_throws() {
        assertThatThrownBy(() -> conversationIn(ConversationStatus.CLOSED).escalate())
                .isInstanceOf(InvalidConversationTransitionException.class);
    }

    @Test
    void escalate_fromEscalated_throws() {
        assertThatThrownBy(() -> conversationIn(ConversationStatus.ESCALATED).escalate())
                .isInstanceOf(InvalidConversationTransitionException.class);
    }

    @Test
    void escalate_fromArchived_throws() {
        assertThatThrownBy(() -> conversationIn(ConversationStatus.ARCHIVED).escalate())
                .isInstanceOf(InvalidConversationTransitionException.class);
    }

    @Test
    void archive_fromOpen_returnsArchivedWithArchivedAt() {
        Conversation archived = conversationIn(ConversationStatus.OPEN).archive();

        assertThat(archived.status()).isEqualTo(ConversationStatus.ARCHIVED);
        assertThat(archived.archivedAt()).isNotNull();
    }

    @Test
    void archive_fromClosed_returnsArchived_preservingClosedAt() {
        Conversation closed = conversationIn(ConversationStatus.OPEN).close();

        Conversation archived = closed.archive();

        assertThat(archived.status()).isEqualTo(ConversationStatus.ARCHIVED);
        assertThat(archived.archivedAt()).isNotNull();
        assertThat(archived.closedAt()).isEqualTo(closed.closedAt());
    }

    @Test
    void archive_fromEscalated_returnsArchived_preservingEscalatedAt() {
        Conversation escalated = conversationIn(ConversationStatus.OPEN).escalate();

        Conversation archived = escalated.archive();

        assertThat(archived.status()).isEqualTo(ConversationStatus.ARCHIVED);
        assertThat(archived.archivedAt()).isNotNull();
        assertThat(archived.escalatedAt()).isEqualTo(escalated.escalatedAt());
    }

    @Test
    void archive_fromArchived_throws() {
        assertThatThrownBy(() -> conversationIn(ConversationStatus.ARCHIVED).archive())
                .isInstanceOf(InvalidConversationTransitionException.class);
    }

    @Test
    void transitions_doNotMutateOriginalInstance() {
        Conversation open = conversationIn(ConversationStatus.OPEN);

        Conversation closed = open.close();

        // equals() is id-based, so compare identity and fields, not equality.
        assertThat(closed).isNotSameAs(open);
        assertThat(open.status()).isEqualTo(ConversationStatus.OPEN);
        assertThat(open.closedAt()).isNull();
        assertThat(open.escalatedAt()).isNull();
        assertThat(open.archivedAt()).isNull();
    }

    private static Conversation newConversation() {
        return Conversation.start(AGENT, IDENTITY, UUID.randomUUID());
    }

    /** Builds a conversation in the given status with consistent lifecycle timestamps. */
    private static Conversation conversationIn(ConversationStatus status) {
        Instant now = Instant.now();
        return Conversation.rehydrate(UUID.randomUUID(), AGENT, IDENTITY.id(), null,
                "whatsapp", "+34612345678",
                status, now, now,
                status == ConversationStatus.CLOSED ? now : null,
                status == ConversationStatus.ESCALATED ? now : null,
                status == ConversationStatus.ARCHIVED ? now : null);
    }
}
