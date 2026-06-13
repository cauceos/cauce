package dev.cauce.llm.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.llm.model.FinishReason;
import dev.cauce.llm.model.LlmResponse;
import org.junit.jupiter.api.Test;

class AnthropicResponseMapperTest {

    private final AnthropicResponseMapper mapper = new AnthropicResponseMapper(new ObjectMapper());

    @Test
    void toDomain_parsesText_usage_andStopReason() throws Exception {
        String body = """
                {"id":"msg_1","model":"claude-sonnet-4-7","role":"assistant",
                 "content":[{"type":"text","text":"Hello there"}],
                 "stop_reason":"end_turn",
                 "usage":{"input_tokens":10,"output_tokens":5}}
                """;

        LlmResponse response = mapper.toDomain(body);

        assertThat(response.content()).isEqualTo("Hello there");
        assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(response.usage().inputTokens()).isEqualTo(10);
        assertThat(response.usage().outputTokens()).isEqualTo(5);
        assertThat(response.usage().totalTokens()).isEqualTo(15);
        assertThat(response.toolCalls()).isEmpty();
    }

    @Test
    void toDomain_mapsMaxTokensStopReason() throws Exception {
        String body = """
                {"content":[{"type":"text","text":"truncated"}],"stop_reason":"max_tokens",
                 "usage":{"input_tokens":1,"output_tokens":2}}
                """;

        assertThat(mapper.toDomain(body).finishReason()).isEqualTo(FinishReason.MAX_TOKENS);
    }

    @Test
    void toDomain_concatenatesMultipleTextBlocks_andIgnoresNonText() throws Exception {
        String body = """
                {"content":[{"type":"text","text":"A"},{"type":"thinking","text":"hidden"},
                            {"type":"text","text":"B"}],
                 "stop_reason":"end_turn","usage":{"input_tokens":0,"output_tokens":0}}
                """;

        assertThat(mapper.toDomain(body).content()).isEqualTo("AB");
    }

    @Test
    void toDomain_parsesToolUseBlocks_intoToolCalls_withTextCoexisting() throws Exception {
        String body = """
                {"content":[{"type":"text","text":"Let me check."},
                            {"type":"tool_use","id":"toolu_1","name":"get_current_time",
                             "input":{"tz":"UTC"}}],
                 "stop_reason":"tool_use","usage":{"input_tokens":3,"output_tokens":4}}
                """;

        LlmResponse response = mapper.toDomain(body);

        assertThat(response.content()).isEqualTo("Let me check.");
        assertThat(response.finishReason()).isEqualTo(FinishReason.TOOL_USE);
        assertThat(response.toolCalls()).hasSize(1);
        ToolCall call = response.toolCalls().get(0);
        assertThat(call.toolCallId()).isEqualTo("toolu_1");
        assertThat(call.toolName()).isEqualTo("get_current_time");
        assertThat(call.input()).containsEntry("tz", "UTC");
    }

    @Test
    void toDomain_unknownStopReason_fallsBackToStop() throws Exception {
        String body = """
                {"content":[{"type":"text","text":"x"}],"stop_reason":"something_new",
                 "usage":{"input_tokens":0,"output_tokens":0}}
                """;

        assertThat(mapper.toDomain(body).finishReason()).isEqualTo(FinishReason.STOP);
    }
}
