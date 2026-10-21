package dev.cauce.orchestration.context;

import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
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
 * persistence, no LLM call. Stateless and instantiable; {@code OrchestratorService} holds one as
 * an injected collaborator.
 *
 * <p>Strategy: reserve {@link #RESERVED_FOR_RESPONSE} tokens of the model's window for the
 * response, then walk the conversation newest-first, including each message while the
 * running estimate fits the remaining (effective) window, and stop at the first message that
 * would overflow. The surviving slice is returned in chronological order.
 *
 * <p>The window always starts at a user turn (ADR 0005, C2). A size cut can land anywhere: on a
 * tool result whose call fell outside, in the middle of a batch of tool calls, or on an
 * assistant turn — histories every provider rejects. So the leading messages before the first
 * USER message of the slice are dropped as well. Everything from that user turn on is contiguous,
 * which keeps every tool call together with its results. When no user turn fits at all, the
 * current turn does not fit the window, and that is a context failure, not a silent cut.
 */
public final class ContextBuilder {

    /** 8K reserved for the model's response plus a 2K safety buffer. */
    static final int RESERVED_FOR_RESPONSE = 10_000;

    /**
     * Builds the invocation context for {@code modelName} from {@code conversationMessages}
     * (assumed chronological, oldest first).
     *
     * @throws MessageTooLargeForContextException if the most recent message alone exceeds the
     *     effective context window, or if no user turn fits in it
     */
    public LlmInvocationContext build(List<Message> conversationMessages, String modelName,
                                      String systemPrompt) {
        int contextWindow = ModelContextWindow.contextWindowFor(modelName);
        int effectiveWindow = contextWindow - RESERVED_FOR_RESPONSE;

        // v1.0: the systemPrompt is NOT counted against the window — it belongs to the Agent,
        // not the conversation history. TODO(v2): if system prompts grow large enough to
        // matter, subtract TokenEstimator.estimate(systemPrompt) from effectiveWindow here.

        List<Message> selected = new ArrayList<>();
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
            selected.add(message);
            runningTokens += messageTokens;
        }

        Collections.reverse(selected); // built newest-first; return chronological
        int firstUserTurn = 0;
        while (firstUserTurn < selected.size() && selected.get(firstUserTurn).role() != MessageRole.USER) {
            runningTokens -= estimateTokens(selected.get(firstUserTurn));
            firstUserTurn++;
        }
        if (!selected.isEmpty() && firstUserTurn == selected.size()) {
            throw new MessageTooLargeForContextException(
                    "The current turn does not fit the effective context window of "
                            + effectiveWindow + " tokens for model '" + modelName
                            + "' from its user message onwards");
        }
        List<LlmMessage> window = selected.subList(firstUserTurn, selected.size()).stream()
                .map(ContextBuilder::toLlmMessage)
                .toList();
        return new LlmInvocationContext(
                window, systemPrompt, runningTokens, contextWindow, RESERVED_FOR_RESPONSE);
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
