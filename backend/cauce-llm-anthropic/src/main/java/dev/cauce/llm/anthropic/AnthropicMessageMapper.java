package dev.cauce.llm.anthropic;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.llm.model.LlmInvocation;
import dev.cauce.llm.model.LlmMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts a neutral {@link LlmInvocation} into the Anthropic Messages API request shape.
 *
 * <p>Anthropic separates the {@code system} prompt from the {@code messages} array (unlike
 * OpenAI, where system is just another message). This mapper concatenates the invocation's
 * {@code systemPrompt} with any {@link dev.cauce.llm.model.LlmRole#SYSTEM} messages into a
 * single {@code system} string, and emits only {@code user}/{@code assistant} turns in
 * {@code messages}. {@code max_tokens} is mandatory for Anthropic, so a default is applied
 * when the invocation does not specify one.
 */
final class AnthropicMessageMapper {

    private final ObjectMapper objectMapper;

    AnthropicMessageMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String toRequestJson(LlmInvocation invocation, int defaultMaxTokens) throws JsonProcessingException {
        return objectMapper.writeValueAsString(toRequest(invocation, defaultMaxTokens));
    }

    AnthropicMessagesRequest toRequest(LlmInvocation invocation, int defaultMaxTokens) {
        List<String> systemParts = new ArrayList<>();
        if (invocation.systemPrompt() != null && !invocation.systemPrompt().isBlank()) {
            systemParts.add(invocation.systemPrompt().strip());
        }

        List<AnthropicMessage> messages = new ArrayList<>();
        for (LlmMessage message : invocation.messages()) {
            switch (message.role()) {
                case SYSTEM -> systemParts.add(message.content());
                case USER -> messages.add(new AnthropicMessage("user", message.content()));
                case ASSISTANT -> messages.add(new AnthropicMessage("assistant", message.content()));
            }
        }

        String system = systemParts.isEmpty() ? null : String.join("\n\n", systemParts);
        int maxTokens = invocation.maxTokens() != null ? invocation.maxTokens() : defaultMaxTokens;
        return new AnthropicMessagesRequest(
                invocation.modelName(), maxTokens, system, messages, invocation.temperature());
    }

    // null system/temperature are omitted from the JSON payload.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AnthropicMessagesRequest(
            @JsonProperty("model") String model,
            @JsonProperty("max_tokens") int maxTokens,
            @JsonProperty("system") String system,
            @JsonProperty("messages") List<AnthropicMessage> messages,
            @JsonProperty("temperature") Double temperature) {
    }

    record AnthropicMessage(
            @JsonProperty("role") String role,
            @JsonProperty("content") String content) {
    }
}
