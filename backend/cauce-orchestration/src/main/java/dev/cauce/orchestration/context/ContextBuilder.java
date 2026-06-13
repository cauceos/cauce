package dev.cauce.orchestration.context;

import dev.cauce.core.message.Message;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolResult;
import dev.cauce.llm.model.LlmMessage;
import dev.cauce.llm.model.LlmRole;
import dev.cauce.orchestration.exception.MessageTooLargeForContextException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds the message list sent to an LLM for one invocation, honouring the target model's
 * context window with a sliding-token-window strategy. Pure application logic: no Spring, no
 * persistence, no LLM call. Stateless and instantiable — a future {@code OrchestratorService}
 * will hold one as an injected, mockable collaborator.
 *
 * <p>Strategy: reserve {@link #RESERVED_FOR_RESPONSE} tokens of the model's window for the
 * response, then walk the conversation newest-first, including each message while the
 * running estimate fits the remaining (effective) window, and stop at the first message that
 * would overflow. The surviving slice is returned in chronological order.
 */
public final class ContextBuilder {

    /** 8K reserved for the model's response plus a 2K safety buffer. */
    static final int RESERVED_FOR_RESPONSE = 10_000;

    /**
     * Builds the invocation context for {@code modelName} from {@code conversationMessages}
     * (assumed chronological, oldest first).
     *
     * @throws MessageTooLargeForContextException if the most recent message alone exceeds the
     *     effective context window
     */
    public LlmInvocationContext build(List<Message> conversationMessages, String modelName,
                                      String systemPrompt) {
        int contextWindow = ModelContextWindow.contextWindowFor(modelName);
        int effectiveWindow = contextWindow - RESERVED_FOR_RESPONSE;

        // v1.0: the systemPrompt is NOT counted against the window — it belongs to the Agent,
        // not the conversation history. TODO(v2): if system prompts grow large enough to
        // matter, subtract TokenEstimator.estimate(systemPrompt) from effectiveWindow here.

        List<LlmMessage> selected = new ArrayList<>();
        int runningTokens = 0;
        int lastIndex = conversationMessages.size() - 1;

        for (int i = lastIndex; i >= 0; i--) {
            Message message = conversationMessages.get(i);
            int messageTokens = estimateTokens(message);

            if (i == lastIndex && messageTokens > effectiveWindow) {
                throw new MessageTooLargeForContextException(
                        "Most recent message estimated at " + messageTokens
                                + " tokens exceeds the effective context window of "
                                + effectiveWindow + " tokens for model '" + modelName + "'");
            }
            if (runningTokens + messageTokens > effectiveWindow) {
                break; // older messages cannot fit either; stop
            }
            selected.add(toLlmMessage(message));
            runningTokens += messageTokens;
        }

        Collections.reverse(selected); // built newest-first; return chronological
        return new LlmInvocationContext(
                selected, systemPrompt, runningTokens, contextWindow, RESERVED_FOR_RESPONSE);
    }

    /**
     * Estimates the wire-token cost of a message. Text content is measured directly; a
     * TOOL_CALL additionally carries its input arguments on the wire (the rendered
     * {@code content()} is only the tool name), so they are added. A TOOL_RESULT's output is
     * already the message content, so no extra is added.
     */
    private static int estimateTokens(Message message) {
        int tokens = TokenEstimator.estimate(message.content());
        if (message.toolContent().orElse(null) instanceof ToolCall call) {
            tokens += TokenEstimator.estimate(String.valueOf(call.input()));
        }
        return tokens;
    }

    /**
     * Translates a domain {@link Message} to a neutral {@link LlmMessage}. Text roles map to
     * their {@link LlmRole}; tool roles use the neutral tool factories, carrying the structured
     * {@code toolContent} so the adapters can assemble each provider's tool wire format.
     */
    private static LlmMessage toLlmMessage(Message message) {
        return switch (message.role()) {
            case USER -> new LlmMessage(LlmRole.USER, message.content());
            case AGENT -> new LlmMessage(LlmRole.ASSISTANT, message.content());
            case SYSTEM -> new LlmMessage(LlmRole.SYSTEM, message.content());
            case TOOL_CALL -> LlmMessage.toolCall((ToolCall) message.toolContent().orElseThrow());
            case TOOL_RESULT -> LlmMessage.toolResult((ToolResult) message.toolContent().orElseThrow());
        };
    }
}
