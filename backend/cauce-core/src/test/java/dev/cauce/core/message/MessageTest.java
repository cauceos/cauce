package dev.cauce.core.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class MessageTest {

    private static final UUID CONVERSATION = UUID.randomUUID();

    @Test
    void from_generatesUuidV7() {
        assertThat(newMessage().id().version()).isEqualTo(7);
    }

    @Test
    void from_assignsFieldsAndTimestamp() {
        Message message = Message.from(CONVERSATION, MessageRole.USER, "Hello");

        assertThat(message.conversationId()).isEqualTo(CONVERSATION);
        assertThat(message.role()).isEqualTo(MessageRole.USER);
        assertThat(message.content()).isEqualTo("Hello");
        assertThat(message.createdAt()).isNotNull();
    }

    @Test
    void from_preservesContentVerbatim_withoutTrimming() {
        Message message = Message.from(CONVERSATION, MessageRole.USER, "  spaced\n");

        assertThat(message.content()).isEqualTo("  spaced\n");
    }

    @Test
    void from_rejectsNullConversationId() {
        assertThatThrownBy(() -> Message.from(null, MessageRole.USER, "Hi"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void from_rejectsNullRole() {
        assertThatThrownBy(() -> Message.from(CONVERSATION, null, "Hi"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void from_rejectsNullContent() {
        assertThatThrownBy(() -> Message.from(CONVERSATION, MessageRole.USER, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void from_rejectsEmptyContent() {
        assertThatThrownBy(() -> Message.from(CONVERSATION, MessageRole.USER, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void from_rejectsBlankContent() {
        assertThatThrownBy(() -> Message.from(CONVERSATION, MessageRole.USER, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toString_truncatesLongContent_andDoesNotExposeFullBody() {
        String longContent = "x".repeat(200);
        Message message = Message.from(CONVERSATION, MessageRole.AGENT, longContent);

        String text = message.toString();

        assertThat(text).contains("x".repeat(50) + "...");
        assertThat(text).doesNotContain("x".repeat(51));
    }

    @Test
    void toString_doesNotTruncateShortContent() {
        Message message = Message.from(CONVERSATION, MessageRole.AGENT, "short");

        assertThat(message.toString()).contains("content=short").doesNotContain("...");
    }

    @Test
    void equalsAndHashCode_areBasedOnId() {
        Message a = newMessage();
        Message sameId = Message.rehydrate(a.id(), UUID.randomUUID(), MessageRole.SYSTEM,
                "other", a.createdAt());
        Message different = newMessage();

        assertThat(a).isEqualTo(sameId);
        assertThat(a).hasSameHashCodeAs(sameId);
        assertThat(a).isNotEqualTo(different);
    }

    private static Message newMessage() {
        return Message.from(CONVERSATION, MessageRole.USER, "Hello");
    }
}
