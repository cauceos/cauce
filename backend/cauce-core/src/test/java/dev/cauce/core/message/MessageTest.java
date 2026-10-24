package dev.cauce.core.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
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

    @Test
    void from_textMessage_hasNoToolContent() {
        assertThat(newMessage().toolContent()).isEmpty();
    }

    @Test
    void from_rejectsToolRoleWithoutToolContent() {
        assertThatThrownBy(() -> Message.from(CONVERSATION, MessageRole.TOOL_CALL, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toolCall_generatesUuidV7AndAssignsFields() {
        ToolCall call = new ToolCall("call-1", "get_current_time", Map.of("tz", "UTC"));

        Message message = Message.toolCall(CONVERSATION, call);

        assertThat(message.id().version()).isEqualTo(7);
        assertThat(message.conversationId()).isEqualTo(CONVERSATION);
        assertThat(message.role()).isEqualTo(MessageRole.TOOL_CALL);
        assertThat(message.content()).isEqualTo("get_current_time"); // rendered from the call
        assertThat(message.toolContent()).contains(call);
        assertThat(message.createdAt()).isNotNull();
    }

    @Test
    void toolResult_assignsFieldsAndRendersOutputAsContent() {
        ToolResult result = ToolResult.success("call-1", "get_current_time", "2026-06-13T10:15:30Z");

        Message message = Message.toolResult(CONVERSATION, result);

        assertThat(message.role()).isEqualTo(MessageRole.TOOL_RESULT);
        assertThat(message.content()).isEqualTo("2026-06-13T10:15:30Z"); // rendered from the output
        assertThat(message.toolContent()).contains(result);
    }

    @Test
    void toolResult_blankOutput_rendersPlaceholderContent() {
        ToolResult result = ToolResult.success("call-1", "noop", "");

        Message message = Message.toolResult(CONVERSATION, result);

        assertThat(message.content()).isEqualTo("[empty result]");
        assertThat(message.toolContent()).contains(result);
    }

    @Test
    void toolCall_rejectsNullCall() {
        assertThatThrownBy(() -> Message.toolCall(CONVERSATION, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void toolResult_rejectsNullResult() {
        assertThatThrownBy(() -> Message.toolResult(CONVERSATION, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rehydrate_toolCallMessage_preservesToolContent() {
        ToolCall call = new ToolCall("call-1", "get_current_time", Map.of());
        Message original = Message.toolCall(CONVERSATION, call);

        Message rehydrated = Message.rehydrate(original.id(), CONVERSATION, MessageRole.TOOL_CALL,
                original.content(), call, original.createdAt());

        assertThat(rehydrated.toolContent()).contains(call);
        assertThat(rehydrated.role()).isEqualTo(MessageRole.TOOL_CALL);
    }

    @Test
    void rehydrate_rejectsRoleAndToolContentMismatch() {
        ToolResult result = ToolResult.success("call-1", "get_current_time", "now");

        assertThatThrownBy(() -> Message.rehydrate(UUID.randomUUID(), CONVERSATION,
                MessageRole.TOOL_CALL, "get_current_time", result, java.time.Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rehydrate_rejectsTextRoleCarryingToolContent() {
        ToolCall call = new ToolCall("call-1", "get_current_time", Map.of());

        assertThatThrownBy(() -> Message.rehydrate(UUID.randomUUID(), CONVERSATION,
                MessageRole.USER, "hi", call, java.time.Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- parts (ADR 0004, decision 2) ---

    @Test
    void parts_aTextMessageIsOneTextPart_andAToolMessageItsOneToolPart() {
        ToolCall call = new ToolCall("call-1", "get_current_time", Map.of());

        assertThat(newMessage().parts()).containsExactly(new MessagePart.Text("Hello"));
        assertThat(Message.toolCall(CONVERSATION, call).parts()).containsExactly(new MessagePart.ToolCallPart(call));
    }

    @Test
    void rehydrateParts_derivesContentAndToolContentFromTheParts() {
        ToolResult result = ToolResult.success("call-1", "get_current_time", "10:15");

        Message text = Message.rehydrateParts(UUID.randomUUID(), CONVERSATION, MessageRole.AGENT,
                List.of(new MessagePart.Text("Hola"), new MessagePart.Text("¿Algo más?")), null, java.time.Instant.now());
        Message tool = Message.rehydrateParts(UUID.randomUUID(), CONVERSATION, MessageRole.TOOL_RESULT,
                List.of(new MessagePart.ToolResultPart(result)), null, java.time.Instant.now());

        assertThat(text.content()).isEqualTo("Hola\n¿Algo más?");
        assertThat(text.toolContent()).isEmpty();
        assertThat(tool.content()).isEqualTo("10:15");
        assertThat(tool.toolContent()).contains(result);
    }

    @Test
    void rehydrateParts_rejectsNoParts_aToolPartInATextRole_andTwoPartsInAToolRole() {
        ToolCall call = new ToolCall("call-1", "get_current_time", Map.of());
        java.time.Instant now = java.time.Instant.now();

        assertThatThrownBy(() -> Message.rehydrateParts(UUID.randomUUID(), CONVERSATION, MessageRole.USER,
                List.of(), null, now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Message.rehydrateParts(UUID.randomUUID(), CONVERSATION, MessageRole.USER,
                List.of(new MessagePart.Text("hi"), new MessagePart.ToolCallPart(call)), null, now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Message.rehydrateParts(UUID.randomUUID(), CONVERSATION, MessageRole.TOOL_CALL,
                List.of(new MessagePart.ToolCallPart(call), new MessagePart.Text("hi")), null, now))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromParts_aUserVoiceNote_readsAsItsTranscript_orAsAVoiceNoteUntilItHasOne() {
        MessagePart.Audio note = MessagePart.Audio.received("audio/ogg", "AwACAgQ", null, 4);

        Message untranscribed = Message.fromParts(CONVERSATION, MessageRole.USER, List.of(note));
        Message transcribed = Message.fromParts(CONVERSATION, MessageRole.USER,
                List.of(note.transcribed("Mesa para cuatro", "a".repeat(64), 6200, 4)));

        assertThat(untranscribed.content()).isEqualTo(Message.UNTRANSCRIBED_AUDIO);
        assertThat(transcribed.content()).isEqualTo("Mesa para cuatro");
        assertThat(transcribed.toolContent()).isEmpty();
        assertThat(transcribed.id().version()).isEqualTo(7);
    }

    @Test
    void fromParts_onlyAUserMessageCarriesAudio() {
        MessagePart.Audio note = MessagePart.Audio.received("audio/ogg", "AwACAgQ", null, null);

        assertThatThrownBy(() -> Message.fromParts(CONVERSATION, MessageRole.AGENT, List.of(note)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("audio");
        assertThatThrownBy(() -> Message.fromParts(CONVERSATION, MessageRole.TOOL_RESULT, List.of(note)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withParts_letsAVoiceNoteLearnItsTranscript_andNothingElseChange() {
        MessagePart.Audio note = MessagePart.Audio.received("audio/ogg", "AwACAgQ", null, 4);
        Message received = Message.fromParts(CONVERSATION, MessageRole.USER, List.of(note, new MessagePart.Text("Hola")));

        Message learned = received.withParts(List.of(note.transcribed("Mesa", "a".repeat(64), 10, 4),
                new MessagePart.Text("Hola")));

        assertThat(learned.id()).isEqualTo(received.id());
        assertThat(learned.content()).isEqualTo("Mesa\nHola");
        assertThatThrownBy(() -> received.withParts(List.of(note, new MessagePart.Text("Adiós"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> received.withParts(List.of(
                MessagePart.Audio.received("audio/ogg", "other-reference", null, 4), new MessagePart.Text("Hola"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> received.withParts(List.of(note))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void audioPart_rejectsWhatCannotBeTrue() {
        assertThatThrownBy(() -> MessagePart.Audio.received(" ", "r", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MessagePart.Audio.received("audio/ogg", "", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MessagePart.Audio("audio/ogg", "r", -1L, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MessagePart.Audio("audio/ogg", "r", null, "NOT-HEX", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Message newMessage() {
        return Message.from(CONVERSATION, MessageRole.USER, "Hello");
    }

    // --- FALLBACK (ADR 0005, C3) ---

    @Test
    void fallback_carriesItsTextAndTheFailureType() {
        UUID conversationId = UUID.randomUUID();

        Message fallback = Message.fallback(conversationId, "Sorry, try again later.", "EMPTY_REPLY");

        assertThat(fallback.role()).isEqualTo(MessageRole.FALLBACK);
        assertThat(fallback.content()).isEqualTo("Sorry, try again later.");
        assertThat(fallback.failureType()).contains("EMPTY_REPLY");
        assertThat(fallback.toolContent()).isEmpty();
    }

    @Test
    void fallback_withoutFailureType_isRejected() {
        assertThatThrownBy(() -> Message.fallback(UUID.randomUUID(), "Sorry.", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void otherRoles_neverCarryAFailureType() {
        assertThat(Message.from(UUID.randomUUID(), MessageRole.AGENT, "Hi").failureType()).isEmpty();
        assertThatThrownBy(() -> Message.rehydrate(UUID.randomUUID(), UUID.randomUUID(), MessageRole.SYSTEM,
                "[orchestration_error] x", null, "LLM_ERROR", java.time.Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
