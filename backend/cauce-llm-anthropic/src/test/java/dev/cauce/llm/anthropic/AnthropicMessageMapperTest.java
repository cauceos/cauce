package dev.cauce.llm.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.llm.anthropic.AnthropicMessageMapper.AnthropicMessage;
import dev.cauce.llm.anthropic.AnthropicMessageMapper.AnthropicMessagesRequest;
import dev.cauce.llm.model.LlmInvocation;
import dev.cauce.llm.model.LlmMessage;
import dev.cauce.llm.spi.LlmCredential;
import java.util.List;
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
