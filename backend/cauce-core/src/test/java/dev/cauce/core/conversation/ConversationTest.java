package dev.cauce.core.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationTest {

    private static final UUID AGENT = UUID.randomUUID();

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
    void start_assignsFields() {
        Conversation conversation = Conversation.start(AGENT, "whatsapp", "+34612345678");

        assertThat(conversation.agentId()).isEqualTo(AGENT);
        assertThat(conversation.channelType()).isEqualTo("whatsapp");
        assertThat(conversation.externalIdentityRef()).isEqualTo("+34612345678");
    }

    @Test
    void start_rejectsNullAgentId() {
        assertThatThrownBy(() -> Conversation.start(null, "whatsapp", "+34612345678"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void start_rejectsBlankChannelType() {
        assertThatThrownBy(() -> Conversation.start(AGENT, "  ", "+34612345678"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void start_rejectsNullExternalIdentityRef() {
        assertThatThrownBy(() -> Conversation.start(AGENT, "whatsapp", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void start_rejectsBlankExternalIdentityRef() {
        assertThatThrownBy(() -> Conversation.start(AGENT, "whatsapp", "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalsAndHashCode_areBasedOnId() {
        Conversation a = newConversation();
        Conversation sameId = Conversation.rehydrate(a.id(), UUID.randomUUID(), "voice", "+34999",
                ConversationStatus.CLOSED, a.startedAt(), a.lastMessageAt(), a.startedAt());
        Conversation different = newConversation();

        assertThat(a).isEqualTo(sameId);
        assertThat(a).hasSameHashCodeAs(sameId);
        assertThat(a).isNotEqualTo(different);
    }

    private static Conversation newConversation() {
        return Conversation.start(AGENT, "whatsapp", "+34612345678");
    }
}
