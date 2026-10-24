package dev.cauce.core.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessagePartsTest {

    private static final List<MessagePart> PARTS = List.of(
            new MessagePart.Text("Hola"),
            new MessagePart.ToolCallPart(new ToolCall("call-1", "search_catalog", Map.of("query", "bravas"))),
            new MessagePart.ToolResultPart(ToolResult.error("call-1", "search_catalog", "boom")));

    @Test
    void toJson_writesEachPartWithItsTypeAndTheV14ToolKeys_inOrder() {
        assertThat(MessageParts.toJson(PARTS)).containsExactly(
                Map.of("type", "text", "text", "Hola"),
                Map.of("type", "tool_call", "tool_call_id", "call-1", "tool_name", "search_catalog",
                        "input", Map.of("query", "bravas")),
                Map.of("type", "tool_result", "tool_call_id", "call-1", "tool_name", "search_catalog",
                        "output", "boom", "is_error", true));
    }

    @Test
    void fromJson_readsBackWhatToJsonWrote() {
        assertThat(MessageParts.fromJson(MessageParts.toJson(PARTS))).isEqualTo(PARTS);
    }

    @Test
    void fromJson_aResultWithoutIsError_isASuccess() {
        assertThat(MessageParts.fromJson(List.of(Map.of("type", "tool_result", "tool_call_id", "c",
                "tool_name", "t", "output", "ok"))))
                .containsExactly(new MessagePart.ToolResultPart(ToolResult.success("c", "t", "ok")));
    }

    @Test
    void audio_writesOnlyWhatIsKnown_andReadsBackTheSame() {
        MessagePart.Audio received = MessagePart.Audio.received("audio/ogg", "AwACAgQ", null, 4);
        MessagePart.Audio transcribed = received.transcribed("Mesa para cuatro", "a".repeat(64), 6200, 4);

        assertThat(MessageParts.toJson(List.of(received))).containsExactly(Map.of("type", "audio",
                "media_type", "audio/ogg", "provider_reference", "AwACAgQ", "duration_seconds", 4));
        assertThat(MessageParts.fromJson(MessageParts.toJson(List.of(received)))).containsExactly(received);
        assertThat(MessageParts.fromJson(MessageParts.toJson(List.of(transcribed)))).containsExactly(transcribed);
    }

    @Test
    void audio_readsBackNumbersOfAnyWidth_asJsonbHandsThem() {
        assertThat(MessageParts.fromJson(List.of(Map.of("type", "audio", "media_type", "audio/ogg",
                "provider_reference", "r", "size_bytes", 6200, "duration_seconds", 4L))))
                .containsExactly(new MessagePart.Audio("audio/ogg", "r", 6200L, null, 4, null));
    }

    @Test
    void toHashedJson_keepsOfAnAudioPartOnlyWhatIsKnownOnArrival() {
        MessagePart.Audio transcribed = MessagePart.Audio.received("audio/ogg", "AwACAgQ", 6200L, 4)
                .transcribed("Mesa para cuatro", "a".repeat(64), 6200, 4);

        assertThat(MessageParts.toHashedJson(List.of(transcribed, new MessagePart.Text("Hola"))))
                .containsExactly(Map.of("type", "audio", "media_type", "audio/ogg", "provider_reference", "AwACAgQ"),
                        Map.of("type", "text", "text", "Hola"));
    }

    @Test
    void fromJson_anUnknownType_fails() {
        assertThatThrownBy(() -> MessageParts.fromJson(List.of(Map.of("type", "video"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("video");
    }
}
