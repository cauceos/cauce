package dev.cauce.llm.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.llm.model.FinishReason;
import dev.cauce.llm.model.LlmResponse;
import dev.cauce.llm.model.LlmUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses an OpenAI {@code /chat/completions} success response into a neutral {@link LlmResponse}:
 * takes the first choice's message content, collects any {@code tool_calls} into neutral
 * {@link ToolCall}s (decoding each {@code function.arguments} JSON string into the input map),
 * normalises {@code finish_reason} to a {@link FinishReason}, and copies token usage. Missing
 * fields degrade gracefully (empty content, zero usage, no tool calls).
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
        List<ToolCall> toolCalls = List.of();
        if (choice != null) {
            if (choice.message() != null && choice.message().content() != null) {
                content = choice.message().content();
            }
            finishReason = choice.finishReason();
            if (choice.message() != null) {
                toolCalls = toToolCalls(choice.message().toolCalls());
            }
        }

        LlmUsage usage = response.usage() == null
                ? LlmUsage.of(0, 0)
                : LlmUsage.of(response.usage().promptTokens(), response.usage().completionTokens());

        return new LlmResponse(content, toolCalls, mapFinishReason(finishReason), usage);
    }

    private List<ToolCall> toToolCalls(List<ResponseToolCall> toolCalls) throws JsonProcessingException {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }
        List<ToolCall> result = new ArrayList<>(toolCalls.size());
        for (ResponseToolCall toolCall : toolCalls) {
            ResponseFunction function = toolCall.function();
            String name = function == null ? null : function.name();
            String arguments = function == null ? null : function.arguments();
            result.add(new ToolCall(toolCall.id(), name, parseArguments(arguments)));
        }
        return result;
    }

    private Map<String, Object> parseArguments(String arguments) throws JsonProcessingException {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(arguments, new TypeReference<Map<String, Object>>() {});
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
            @JsonProperty("content") String content,
            @JsonProperty("tool_calls") List<ResponseToolCall> toolCalls) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResponseToolCall(
            @JsonProperty("id") String id,
            @JsonProperty("type") String type,
            @JsonProperty("function") ResponseFunction function) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResponseFunction(
            @JsonProperty("name") String name,
            @JsonProperty("arguments") String arguments) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens) {
    }
}
