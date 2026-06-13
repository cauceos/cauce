package dev.cauce.llm.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolDefinition;
import dev.cauce.core.tool.ToolResult;
import dev.cauce.llm.model.LlmInvocation;
import dev.cauce.llm.model.LlmMessage;
import dev.cauce.llm.model.LlmRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converts a neutral {@link LlmInvocation} into the OpenAI {@code /chat/completions} request shape.
 *
 * <p>Unlike Anthropic, OpenAI treats the system prompt as just another message, so the invocation's
 * {@code systemPrompt} (when present) is emitted as a leading {@code system} message, followed by
 * the conversation turns. {@code max_tokens} is always sent (defaulted when the invocation omits
 * one); {@code temperature} is omitted from the payload when null.
 *
 * <p>Tools: {@link LlmInvocation#tools() tool definitions} become the {@code tools} array
 * ({@code {type:"function", function:{name, description, parameters}}}). The flat neutral tool
 * messages are assembled into OpenAI's form: a run of {@link ToolCall}s (with any preceding
 * assistant text) collapses into one {@code assistant} message carrying a {@code tool_calls} array
 * — each call's input is encoded as a JSON <em>string</em> in {@code function.arguments} — while
 * each {@link ToolResult} becomes its own {@code role:"tool"} message keyed by {@code tool_call_id}.
 * OpenAI has no {@code is_error} field, so an errored result's text is conveyed in the tool
 * message {@code content}.
 */
final class OpenAiMessageMapper {

    private final ObjectMapper objectMapper;

    OpenAiMessageMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String toRequestJson(LlmInvocation invocation, int defaultMaxTokens) throws JsonProcessingException {
        return objectMapper.writeValueAsString(toRequest(invocation, defaultMaxTokens));
    }

    ChatCompletionsRequest toRequest(LlmInvocation invocation, int defaultMaxTokens)
            throws JsonProcessingException {
        List<ChatMessage> messages = new ArrayList<>();
        if (invocation.systemPrompt() != null && !invocation.systemPrompt().isBlank()) {
            messages.add(ChatMessage.text("system", invocation.systemPrompt().strip()));
        }

        List<LlmMessage> turns = invocation.messages();
        int i = 0;
        while (i < turns.size()) {
            LlmMessage message = turns.get(i);
            if (message.role() == LlmRole.ASSISTANT) {
                // Collapse a consecutive assistant run (text + tool calls) into one message.
                StringBuilder content = new StringBuilder();
                List<ToolCallJson> toolCalls = new ArrayList<>();
                while (i < turns.size() && turns.get(i).role() == LlmRole.ASSISTANT) {
                    LlmMessage turn = turns.get(i);
                    if (turn.toolContent() instanceof ToolCall call) {
                        toolCalls.add(toToolCallJson(call));
                    } else if (!turn.content().isEmpty()) {
                        if (content.length() > 0) {
                            content.append('\n');
                        }
                        content.append(turn.content());
                    }
                    i++;
                }
                messages.add(ChatMessage.assistant(
                        content.length() == 0 ? null : content.toString(),
                        toolCalls.isEmpty() ? null : toolCalls));
            } else if (message.toolContent() instanceof ToolResult result) {
                messages.add(ChatMessage.tool(result.toolCallId(), result.output()));
                i++;
            } else {
                messages.add(ChatMessage.text(toRole(message.role()), message.content()));
                i++;
            }
        }

        int maxTokens = invocation.maxTokens() != null ? invocation.maxTokens() : defaultMaxTokens;
        return new ChatCompletionsRequest(
                invocation.modelName(), messages, maxTokens, invocation.temperature(),
                toTools(invocation.tools()));
    }

    private ToolCallJson toToolCallJson(ToolCall call) throws JsonProcessingException {
        String arguments = objectMapper.writeValueAsString(call.input());
        return new ToolCallJson(call.toolCallId(), "function",
                new FunctionCall(call.toolName(), arguments));
    }

    private static List<ToolJson> toTools(List<ToolDefinition> tools) {
        if (tools.isEmpty()) {
            return null; // omitted from the payload for a plain completion request
        }
        List<ToolJson> result = new ArrayList<>(tools.size());
        for (ToolDefinition tool : tools) {
            result.add(new ToolJson("function",
                    new FunctionDef(tool.name(), tool.description(), tool.inputSchema())));
        }
        return result;
    }

    private static String toRole(LlmRole role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
        };
    }

    // null temperature/tools are omitted from the JSON payload.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ChatCompletionsRequest(
            @JsonProperty("model") String model,
            @JsonProperty("messages") List<ChatMessage> messages,
            @JsonProperty("max_tokens") int maxTokens,
            @JsonProperty("temperature") Double temperature,
            @JsonProperty("tools") List<ToolJson> tools) {
    }

    // null content/tool_calls/tool_call_id are omitted, so each message carries only the keys
    // OpenAI expects for its role (text, assistant-with-tool_calls, or tool result).
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ChatMessage(
            @JsonProperty("role") String role,
            @JsonProperty("content") String content,
            @JsonProperty("tool_calls") List<ToolCallJson> toolCalls,
            @JsonProperty("tool_call_id") String toolCallId) {

        static ChatMessage text(String role, String content) {
            return new ChatMessage(role, content, null, null);
        }

        static ChatMessage assistant(String content, List<ToolCallJson> toolCalls) {
            return new ChatMessage("assistant", content, toolCalls, null);
        }

        static ChatMessage tool(String toolCallId, String content) {
            return new ChatMessage("tool", content, null, toolCallId);
        }
    }

    record ToolCallJson(
            @JsonProperty("id") String id,
            @JsonProperty("type") String type,
            @JsonProperty("function") FunctionCall function) {
    }

    record FunctionCall(
            @JsonProperty("name") String name,
            @JsonProperty("arguments") String arguments) {
    }

    record ToolJson(
            @JsonProperty("type") String type,
            @JsonProperty("function") FunctionDef function) {
    }

    record FunctionDef(
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("parameters") Map<String, Object> parameters) {
    }
}
