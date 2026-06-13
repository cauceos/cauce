package dev.cauce.llm.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.llm.model.FinishReason;
import dev.cauce.llm.model.LlmResponse;
import org.junit.jupiter.api.Test;

class OpenAiResponseMapperTest {

    private final OpenAiResponseMapper mapper = new OpenAiResponseMapper(new ObjectMapper());

    @Test
    void toDomain_parsesContentUsageAndStop() throws Exception {
        String body = """
                {"choices":[{"message":{"role":"assistant","content":"Hi there"},
                 "finish_reason":"stop"}],
                 "usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}
                """;

        LlmResponse response = mapper.toDomain(body);

        assertThat(response.content()).isEqualTo("Hi there");
        assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(response.usage().inputTokens()).isEqualTo(7);
        assertThat(response.usage().outputTokens()).isEqualTo(3);
        assertThat(response.usage().totalTokens()).isEqualTo(10);
    }

    @Test
    void toDomain_mapsLengthToMaxTokens() throws Exception {
        String body = """
                {"choices":[{"message":{"content":"truncated"},"finish_reason":"length"}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1}}
                """;

        assertThat(mapper.toDomain(body).finishReason()).isEqualTo(FinishReason.MAX_TOKENS);
    }

    @Test
    void toDomain_mapsToolCallsToToolUse() throws Exception {
        String body = """
                {"choices":[{"message":{"content":null},"finish_reason":"tool_calls"}]}
                """;

        LlmResponse response = mapper.toDomain(body);

        assertThat(response.finishReason()).isEqualTo(FinishReason.TOOL_USE);
        assertThat(response.content()).isEmpty();
    }

    @Test
    void toDomain_parsesToolCalls_decodingArgumentsJsonString() throws Exception {
        String body = """
                {"choices":[{"message":{"role":"assistant","content":null,
                  "tool_calls":[{"id":"call_1","type":"function",
                    "function":{"name":"get_current_time","arguments":"{\\"tz\\":\\"UTC\\"}"}}]},
                 "finish_reason":"tool_calls"}]}
                """;

        LlmResponse response = mapper.toDomain(body);

        assertThat(response.finishReason()).isEqualTo(FinishReason.TOOL_USE);
        assertThat(response.toolCalls()).hasSize(1);
        ToolCall call = response.toolCalls().get(0);
        assertThat(call.toolCallId()).isEqualTo("call_1");
        assertThat(call.toolName()).isEqualTo("get_current_time");
        assertThat(call.input()).containsEntry("tz", "UTC");
    }

    @Test
    void toDomain_withoutUsageOrChoices_returnsEmptyContentAndZeroUsage() throws Exception {
        LlmResponse response = mapper.toDomain("{}");

        assertThat(response.content()).isEmpty();
        assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(response.usage().totalTokens()).isZero();
    }
}
