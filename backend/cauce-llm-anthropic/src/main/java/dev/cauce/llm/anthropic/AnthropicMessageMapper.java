package dev.cauce.llm.anthropic;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolContent;
import dev.cauce.core.tool.ToolDefinition;
import dev.cauce.core.tool.ToolResult;
import dev.cauce.llm.model.LlmInvocation;
import dev.cauce.llm.model.LlmMessage;
import dev.cauce.llm.model.LlmRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converts a neutral {@link LlmInvocation} into the Anthropic Messages API request shape.
 *
 * <p>Anthropic separates the {@code system} prompt from the {@code messages} array (unlike
 * OpenAI, where system is just another message). This mapper concatenates the invocation's
 * {@code systemPrompt} with any {@link LlmRole#SYSTEM} messages into a single {@code system}
 * string, and emits {@code user}/{@code assistant} turns in {@code messages}.
 *
 * <p>Tools: {@link LlmInvocation#tools() tool definitions} become the {@code tools} array
 * ({@code name}, {@code description}, {@code input_schema}). The flat neutral tool messages are
 * assembled into Anthropic's grouped block form: an {@link ToolCall} becomes a {@code tool_use}
 * block in an {@code assistant} message, and a {@link ToolResult} becomes a {@code tool_result}
 * block in a {@code user} message. Consecutive same-role messages are merged into one message
 * with a content-block array; a lone text turn keeps the plain-string {@code content} form, so
 * text-only requests are unchanged.
 *
 * <p>{@code max_tokens} is mandatory for Anthropic, so a default is applied when the invocation
 * does not specify one.
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
        String groupRole = null;
        List<AnthropicContentBlock> groupBlocks = null;
        for (LlmMessage message : invocation.messages()) {
            if (message.role() == LlmRole.SYSTEM) {
                systemParts.add(message.content());
                continue;
            }
            String role = message.role() == LlmRole.USER ? "user" : "assistant";
            if (groupBlocks != null && groupRole.equals(role)) {
                groupBlocks.add(toBlock(message));
            } else {
                flush(messages, groupRole, groupBlocks);
                groupRole = role;
                groupBlocks = new ArrayList<>();
                groupBlocks.add(toBlock(message));
            }
        }
        flush(messages, groupRole, groupBlocks);

        String system = systemParts.isEmpty() ? null : String.join("\n\n", systemParts);
        int maxTokens = invocation.maxTokens() != null ? invocation.maxTokens() : defaultMaxTokens;
        return new AnthropicMessagesRequest(invocation.modelName(), maxTokens, system, messages,
                invocation.temperature(), toTools(invocation.tools()));
    }

    /**
     * Emits the accumulated group. A lone text block is sent as plain-string content (the
     * text-only request shape); anything else is sent as a content-block array.
     */
    private static void flush(List<AnthropicMessage> messages, String role,
                              List<AnthropicContentBlock> blocks) {
        if (blocks == null) {
            return;
        }
        if (blocks.size() == 1 && "text".equals(blocks.get(0).type())) {
            messages.add(new AnthropicMessage(role, blocks.get(0).text()));
        } else {
            messages.add(new AnthropicMessage(role, blocks));
        }
    }

    private static AnthropicContentBlock toBlock(LlmMessage message) {
        ToolContent toolContent = message.toolContent();
        if (toolContent instanceof ToolCall call) {
            return AnthropicContentBlock.toolUse(call.toolCallId(), call.toolName(), call.input());
        }
        if (toolContent instanceof ToolResult result) {
            return AnthropicContentBlock.toolResult(
                    result.toolCallId(), result.output(), result.isError());
        }
        return AnthropicContentBlock.text(message.content());
    }

    private static List<AnthropicTool> toTools(List<ToolDefinition> tools) {
        if (tools.isEmpty()) {
            return null; // omitted from the payload for a plain completion request
        }
        List<AnthropicTool> result = new ArrayList<>(tools.size());
        for (ToolDefinition tool : tools) {
            result.add(new AnthropicTool(tool.name(), tool.description(), tool.inputSchema()));
        }
        return result;
    }

    // null system/temperature/tools are omitted from the JSON payload.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AnthropicMessagesRequest(
            @JsonProperty("model") String model,
            @JsonProperty("max_tokens") int maxTokens,
            @JsonProperty("system") String system,
            @JsonProperty("messages") List<AnthropicMessage> messages,
            @JsonProperty("temperature") Double temperature,
            @JsonProperty("tools") List<AnthropicTool> tools) {
    }

    record AnthropicTool(
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("input_schema") Map<String, Object> inputSchema) {
    }

    /** A message whose {@code content} is either a plain String (text turn) or a block array. */
    record AnthropicMessage(
            @JsonProperty("role") String role,
            @JsonProperty("content") Object content) {
    }

    // One record covers all block kinds; null fields are omitted, so each block serialises to
    // exactly the keys Anthropic expects for its type.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AnthropicContentBlock(
            @JsonProperty("type") String type,
            @JsonProperty("text") String text,
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("input") Map<String, Object> input,
            @JsonProperty("tool_use_id") String toolUseId,
            @JsonProperty("content") String content,
            @JsonProperty("is_error") Boolean isError) {

        static AnthropicContentBlock text(String text) {
            return new AnthropicContentBlock("text", text, null, null, null, null, null, null);
        }

        static AnthropicContentBlock toolUse(String id, String name, Map<String, Object> input) {
            return new AnthropicContentBlock("tool_use", null, id, name, input, null, null, null);
        }

        static AnthropicContentBlock toolResult(String toolUseId, String content, boolean isError) {
            return new AnthropicContentBlock(
                    "tool_result", null, null, null, null, toolUseId, content, isError);
        }
    }
}
