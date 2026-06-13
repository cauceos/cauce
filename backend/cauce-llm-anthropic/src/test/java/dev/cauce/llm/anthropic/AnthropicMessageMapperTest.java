package dev.cauce.llm.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolDefinition;
import dev.cauce.core.tool.ToolResult;
import dev.cauce.llm.anthropic.AnthropicMessageMapper.AnthropicContentBlock;
import dev.cauce.llm.anthropic.AnthropicMessageMapper.AnthropicMessage;
import dev.cauce.llm.anthropic.AnthropicMessageMapper.AnthropicMessagesRequest;
import dev.cauce.llm.model.LlmInvocation;
import dev.cauce.llm.model.LlmMessage;
import dev.cauce.llm.spi.LlmCredential;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AnthropicMessageMapperTest {

    private final AnthropicMessageMapper mapper = new AnthropicMessageMapper(new ObjectMapper());

    @Test
    void toRequest_mergesSystemPromptWithSystemMessages_andEmitsOnlyUserAssistant() {
        LlmInvocation invocation = LlmInvocation.builder()
                .modelName("claude-sonnet-4-7")
                .systemPrompt("Base system.")
                .messages(List.of(
                        LlmMessage.system("Extra system."),
                        LlmMessage.user("Hi"),
                        LlmMessage.assistant("Hello")))
                .credential(cred())
                .build();

        AnthropicMessagesRequest request = mapper.toRequest(invocation, 4096);

        assertThat(request.model()).isEqualTo("claude-sonnet-4-7");
        assertThat(request.system()).isEqualTo("Base system.\n\nExtra system.");
        assertThat(request.messages())
                .extracting(AnthropicMessage::role)
                .containsExactly("user", "assistant");
    }

    @Test
    void toRequest_usesDefaultMaxTokensWhenInvocationHasNone() {
        AnthropicMessagesRequest request = mapper.toRequest(baseInvocation().build(), 4096);

        assertThat(request.maxTokens()).isEqualTo(4096);
    }

    @Test
    void toRequest_usesInvocationMaxTokensWhenSet() {
        AnthropicMessagesRequest request = mapper.toRequest(baseInvocation().maxTokens(1000).build(), 4096);

        assertThat(request.maxTokens()).isEqualTo(1000);
    }

    @Test
    void toRequest_systemIsNullWhenNoSystemContent() {
        AnthropicMessagesRequest request = mapper.toRequest(baseInvocation().build(), 4096);

        assertThat(request.system()).isNull();
    }

    @Test
    void toRequestJson_omitsNullSystemAndTemperature_andUsesSnakeCaseMaxTokens() throws Exception {
        String json = mapper.toRequestJson(baseInvocation().build(), 4096);

        assertThat(json).contains("\"max_tokens\":4096");
        assertThat(json).doesNotContain("system");
        assertThat(json).doesNotContain("temperature");
    }

    @Test
    void toRequestJson_includesTemperatureWhenSet() throws Exception {
        String json = mapper.toRequestJson(baseInvocation().temperature(0.7).build(), 4096);

        assertThat(json).contains("\"temperature\":0.7");
    }

    @Test
    void toRequest_textMessage_keepsPlainStringContent() {
        AnthropicMessagesRequest request = mapper.toRequest(baseInvocation().build(), 4096);

        assertThat(request.messages().get(0).content()).isEqualTo("Hi");
    }

    @Test
    void toRequestJson_serializesToolsArray_withSnakeCaseInputSchema() throws Exception {
        ToolDefinition clock = new ToolDefinition("get_current_time", "Returns the current time",
                Map.of("type", "object", "properties", Map.of()));

        String json = mapper.toRequestJson(baseInvocation().tools(List.of(clock)).build(), 4096);

        assertThat(json).contains("\"tools\":[");
        assertThat(json).contains("\"name\":\"get_current_time\"");
        assertThat(json).contains("\"input_schema\":{");
    }

    @Test
    void toRequest_groupsAssistantTextAndToolCallIntoOneAssistantMessage() {
        LlmInvocation invocation = baseInvocation()
                .messages(List.of(
                        LlmMessage.user("What time is it?"),
                        LlmMessage.assistant("Let me check."),
                        LlmMessage.toolCall(new ToolCall("call-1", "get_current_time", Map.of()))))
                .build();

        AnthropicMessagesRequest request = mapper.toRequest(invocation, 4096);

        assertThat(request.messages()).hasSize(2);
        AnthropicMessage assistant = request.messages().get(1);
        assertThat(assistant.role()).isEqualTo("assistant");
        List<AnthropicContentBlock> blocks = blocksOf(assistant);
        assertThat(blocks).extracting(AnthropicContentBlock::type).containsExactly("text", "tool_use");
        assertThat(blocks.get(1).id()).isEqualTo("call-1");
        assertThat(blocks.get(1).name()).isEqualTo("get_current_time");
    }

    @Test
    void toRequest_groupsToolResultsIntoOneUserMessage_withIsErrorRoundTrip() throws Exception {
        LlmInvocation invocation = baseInvocation()
                .messages(List.of(
                        LlmMessage.toolResult(
                                ToolResult.success("call-1", "get_current_time", "2026-06-13T10:15:30Z")),
                        LlmMessage.toolResult(
                                ToolResult.error("call-2", "get_current_time", "boom"))))
                .build();

        AnthropicMessagesRequest request = mapper.toRequest(invocation, 4096);

        assertThat(request.messages()).hasSize(1);
        AnthropicMessage user = request.messages().get(0);
        assertThat(user.role()).isEqualTo("user");
        List<AnthropicContentBlock> blocks = blocksOf(user);
        assertThat(blocks).extracting(AnthropicContentBlock::type)
                .containsExactly("tool_result", "tool_result");
        assertThat(blocks.get(0).toolUseId()).isEqualTo("call-1");
        assertThat(blocks.get(0).isError()).isFalse();
        assertThat(blocks.get(1).isError()).isTrue();

        String json = mapper.toRequestJson(invocation, 4096);
        assertThat(json).contains("\"tool_use_id\":\"call-1\"").contains("\"is_error\":true");
    }

    @SuppressWarnings("unchecked")
    private static List<AnthropicContentBlock> blocksOf(AnthropicMessage message) {
        assertThat(message.content()).isInstanceOf(List.class);
        return (List<AnthropicContentBlock>) message.content();
    }

    private static LlmInvocation.Builder baseInvocation() {
        return LlmInvocation.builder()
                .modelName("claude-sonnet-4-7")
                .messages(List.of(LlmMessage.user("Hi")))
                .credential(cred());
    }

    private static LlmCredential cred() {
        return new LlmCredential() {
            @Override
            public String getApiKey() {
                return "sk-test";
            }

            @Override
            public Optional<String> getOrganizationId() {
                return Optional.empty();
            }
        };
    }
}
