package dev.cauce.llm.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.llm.model.LlmInvocation;
import dev.cauce.llm.model.LlmMessage;
import dev.cauce.llm.model.LlmRole;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts a neutral {@link LlmInvocation} into the OpenAI {@code /chat/completions} request shape.
 *
 * <p>Unlike Anthropic, OpenAI treats the system prompt as just another message, so the invocation's
 * {@code systemPrompt} (when present) is emitted as a leading {@code system} message, followed by
 * the conversation turns mapped to {@code user}/{@code assistant}/{@code system} roles.
 * {@code max_tokens} is always sent (defaulted when the invocation omits one); {@code temperature}
 * is omitted from the payload when null.
 */
final class OpenAiMessageMapper {

    private final ObjectMapper objectMapper;

    OpenAiMessageMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String toRequestJson(LlmInvocation invocation, int defaultMaxTokens) throws JsonProcessingException {
        return objectMapper.writeValueAsString(toRequest(invocation, defaultMaxTokens));
    }

    ChatCompletionsRequest toRequest(LlmInvocation invocation, int defaultMaxTokens) {
        List<ChatMessage> messages = new ArrayList<>();
        if (invocation.systemPrompt() != null && !invocation.systemPrompt().isBlank()) {
            messages.add(new ChatMessage("system", invocation.systemPrompt().strip()));
        }
        for (LlmMessage message : invocation.messages()) {
            messages.add(new ChatMessage(toRole(message.role()), message.content()));
        }

        int maxTokens = invocation.maxTokens() != null ? invocation.maxTokens() : defaultMaxTokens;
        return new ChatCompletionsRequest(
                invocation.modelName(), messages, maxTokens, invocation.temperature());
    }

    private static String toRole(LlmRole role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
        };
    }

    // null temperature is omitted from the JSON payload.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ChatCompletionsRequest(
            @JsonProperty("model") String model,
            @JsonProperty("messages") List<ChatMessage> messages,
            @JsonProperty("max_tokens") int maxTokens,
            @JsonProperty("temperature") Double temperature) {
    }

    record ChatMessage(
            @JsonProperty("role") String role,
            @JsonProperty("content") String content) {
    }
}
