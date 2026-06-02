package dev.cauce.llm.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.llm.model.LlmInvocation;
import dev.cauce.llm.model.LlmMessage;
import dev.cauce.llm.spi.LlmCredential;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OpenAiMessageMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenAiMessageMapper mapper = new OpenAiMessageMapper(objectMapper);

    @Test
    void toRequest_prependsSystemPrompt_andMapsRolesInOrder() throws Exception {
        LlmInvocation invocation = LlmInvocation.builder()
                .modelName("gpt-4o")
                .systemPrompt("You are helpful.")
                .messages(List.of(
                        LlmMessage.user("Hi"),
                        LlmMessage.assistant("Hello"),
                        LlmMessage.user("How are you?")))
                .temperature(0.5)
                .maxTokens(256)
                .credential(cred())
                .build();

        JsonNode json = objectMapper.readTree(mapper.toRequestJson(invocation, 4096));

        assertThat(json.get("model").asText()).isEqualTo("gpt-4o");
        assertThat(json.get("max_tokens").asInt()).isEqualTo(256);
        assertThat(json.get("temperature").asDouble()).isEqualTo(0.5);

        JsonNode messages = json.get("messages");
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0).get("role").asText()).isEqualTo("system");
        assertThat(messages.get(0).get("content").asText()).isEqualTo("You are helpful.");
        assertThat(messages.get(1).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(1).get("content").asText()).isEqualTo("Hi");
        assertThat(messages.get(2).get("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(3).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(3).get("content").asText()).isEqualTo("How are you?");
    }

    @Test
    void toRequest_withoutSystemPrompt_omitsSystemMessage_andDefaultsMaxTokens_omitsTemperature()
            throws Exception {
        LlmInvocation invocation = LlmInvocation.builder()
                .modelName("llama3.2")
                .messages(List.of(LlmMessage.user("Hi")))
                .credential(cred())
                .build();

        JsonNode json = objectMapper.readTree(mapper.toRequestJson(invocation, 4096));

        assertThat(json.get("messages")).hasSize(1);
        assertThat(json.get("messages").get(0).get("role").asText()).isEqualTo("user");
        assertThat(json.get("max_tokens").asInt()).isEqualTo(4096);
        assertThat(json.has("temperature")).isFalse(); // null temperature is omitted
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
