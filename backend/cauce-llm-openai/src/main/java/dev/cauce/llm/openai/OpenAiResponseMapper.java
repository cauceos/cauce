package dev.cauce.llm.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.llm.model.FinishReason;
import dev.cauce.llm.model.LlmResponse;
import dev.cauce.llm.model.LlmUsage;
import java.util.List;

/**
 * Parses an OpenAI {@code /chat/completions} success response into a neutral {@link LlmResponse}:
 * takes the first choice's message content, normalises {@code finish_reason} to a
 * {@link FinishReason}, and copies token usage. Tool-call deltas are not yet handled, so the
 * response carries an empty tool-call list. Missing fields degrade gracefully (empty content,
 * zero usage).
 */
final class OpenAiResponseMapper {

    private final ObjectMapper objectMapper;

    OpenAiResponseMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    LlmResponse toDomain(String responseBody) throws JsonProcessingException {
        ChatCompletionsResponse response =
                objectMapper.readValue(responseBody, ChatCompletionsResponse.class);

        Choice choice = (response.choices() == null || response.choices().isEmpty())
                ? null
                : response.choices().get(0);

        String content = "";
        String finishReason = null;
        if (choice != null) {
            if (choice.message() != null && choice.message().content() != null) {
                content = choice.message().content();
            }
            finishReason = choice.finishReason();
        }

        LlmUsage usage = response.usage() == null
                ? LlmUsage.of(0, 0)
                : LlmUsage.of(response.usage().promptTokens(), response.usage().completionTokens());

        return new LlmResponse(content, List.of(), mapFinishReason(finishReason), usage);
    }

    private static FinishReason mapFinishReason(String finishReason) {
        if (finishReason == null) {
            return FinishReason.STOP;
        }
        return switch (finishReason) {
            case "length" -> FinishReason.MAX_TOKENS;
            case "tool_calls", "function_call" -> FinishReason.TOOL_USE;
            default -> FinishReason.STOP; // stop, content_filter, and anything else
        };
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatCompletionsResponse(
            @JsonProperty("choices") List<Choice> choices,
            @JsonProperty("usage") Usage usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(
            @JsonProperty("message") ResponseMessage message,
            @JsonProperty("finish_reason") String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResponseMessage(
            @JsonProperty("role") String role,
            @JsonProperty("content") String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens) {
    }
}
