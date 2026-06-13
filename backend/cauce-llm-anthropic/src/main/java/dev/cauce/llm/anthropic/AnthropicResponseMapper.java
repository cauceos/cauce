package dev.cauce.llm.anthropic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.llm.model.FinishReason;
import dev.cauce.llm.model.LlmResponse;
import dev.cauce.llm.model.LlmUsage;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Parses an Anthropic Messages API success response into a neutral {@link LlmResponse}:
 * concatenates the {@code text} content blocks, collects {@code tool_use} blocks into neutral
 * {@link ToolCall}s (carrying the {@code id} as the correlation id and the {@code input}
 * verbatim), normalises {@code stop_reason} to a {@link FinishReason}, and copies token usage.
 */
final class AnthropicResponseMapper {

    private final ObjectMapper objectMapper;

    AnthropicResponseMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    LlmResponse toDomain(String responseBody) throws JsonProcessingException {
        AnthropicMessagesResponse response =
                objectMapper.readValue(responseBody, AnthropicMessagesResponse.class);
        List<ContentBlock> blocks = response.content() == null ? List.of() : response.content();

        String text = blocks.stream()
                .filter(block -> "text".equals(block.type()) && block.text() != null)
                .map(ContentBlock::text)
                .collect(Collectors.joining());

        List<ToolCall> toolCalls = blocks.stream()
                .filter(block -> "tool_use".equals(block.type()))
                .map(block -> new ToolCall(block.id(), block.name(),
                        block.input() == null ? Map.of() : block.input()))
                .toList();

        LlmUsage usage = response.usage() == null
                ? LlmUsage.of(0, 0)
                : LlmUsage.of(response.usage().inputTokens(), response.usage().outputTokens());

        return new LlmResponse(text, toolCalls, mapStopReason(response.stopReason()), usage);
    }

    private static FinishReason mapStopReason(String stopReason) {
        if (stopReason == null) {
            return FinishReason.STOP;
        }
        return switch (stopReason) {
            case "max_tokens" -> FinishReason.MAX_TOKENS;
            case "tool_use" -> FinishReason.TOOL_USE;
            default -> FinishReason.STOP; // end_turn, stop_sequence, and anything else
        };
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AnthropicMessagesResponse(
            @JsonProperty("content") List<ContentBlock> content,
            @JsonProperty("stop_reason") String stopReason,
            @JsonProperty("usage") Usage usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContentBlock(
            @JsonProperty("type") String type,
            @JsonProperty("text") String text,
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("input") Map<String, Object> input) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(
            @JsonProperty("input_tokens") int inputTokens,
            @JsonProperty("output_tokens") int outputTokens) {
    }
}
