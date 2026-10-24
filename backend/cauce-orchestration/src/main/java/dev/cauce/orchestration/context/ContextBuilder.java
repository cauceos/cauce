package dev.cauce.orchestration.context;

import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessagePart;
import dev.cauce.core.message.MessageRole;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolDefinition;
import dev.cauce.core.tool.ToolResult;
import dev.cauce.llm.model.LlmMessage;
import dev.cauce.llm.model.LlmRole;
import dev.cauce.orchestration.exception.MessageTooLargeForContextException;
import dev.cauce.orchestration.exception.PromptDoesNotFitContextException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the message list sent to an LLM for one invocation, honouring the target model's
 * context window with a sliding-token-window strategy. Pure application logic: no Spring, no
 * persistence, no LLM call. Stateless and instantiable; {@code OrchestratorService} holds one as
 * an injected collaborator.
 *
 * <p>Strategy: reserve {@link #RESERVED_FOR_RESPONSE} tokens of the model's window for the
 * response and the estimate of what every request carries besides the history — the system prompt
 * and the tool schemas ({@link #fixedTokens}) — then walk the conversation newest-first, including
 * each message while the running estimate fits the remaining (effective) window, and stop at the
 * first message that would overflow. The surviving slice is returned in chronological order.
 *
 * <p>When the prompt and tools leave no room for the current message, the agent does not fit the
 * window it is served: that is the agent's configuration to fix, and it fails
 * ({@link PromptDoesNotFitContextException}), never sent for the provider to cut silently.
 *
 * <p>The window always starts at a user turn (ADR 0005, C2). A size cut can land anywhere: on a
 * tool result whose call fell outside, in the middle of a batch of tool calls, or on an
 * assistant turn — histories every provider rejects. So the leading messages before the first
 * USER message of the slice are dropped as well. Everything from that user turn on is contiguous,
 * which keeps every tool call together with its results. When no user turn fits at all, the
 * current turn does not fit the window, and that is a context failure, not a silent cut.
 *
 * <p>FALLBACK messages are skipped entirely (ADR 0005, C3): they are the platform's reply to a
 * failed invocation, not a turn of the agent, and the model must not read them as its own.
 */
public final class ContextBuilder {

    /**
     * 8K reserved for the model's response plus a 2K safety buffer — in a window large enough to
     * keep {@link #MIN_HISTORY} after it. A smaller window (a local server's, e.g. Ollama's 4096)
     * reserves half of itself instead ({@link #reservedForResponse}), so that it still holds a
     * conversation.
     */
    static final int RESERVED_FOR_RESPONSE = 10_000;

    /** History a window must keep after the standard reservation for that reservation to apply. */
    static final int MIN_HISTORY = 4_096;

    /**
     * What a provider adds once when tools are offered: the tool-use instructions it writes into the
     * prompt (Anthropic documents a few hundred tokens; an Ollama chat template writes its own).
     * Conservative, like {@link TokenEstimator}: an overshoot costs history, an undershoot a cut.
     */
    static final int TOOL_USE_OVERHEAD = 400;

    /** What each tool's wire wrapping adds to its name, description and schema. */
    static final int PER_TOOL_OVERHEAD = 20;

    /**
     * How the model reads a voice note (ADR 0012): its transcript, marked as one, so that the agent
     * knows the text was heard, not written, and confirms what a mishearing would change.
     */
    public static final String TRANSCRIBED_VOICE_NOTE = "[Transcribed voice note] ";

    /** What stands in for a past lookup's result when the agent elides them (deferred R1). */
    public static final String ELIDED_RESULT = "resultado omitido; vuelve a consultar si lo necesitas";

    /**
     * Builds the invocation context for {@code modelName} from {@code conversationMessages}
     * (assumed chronological, oldest first).
     *
     * @throws MessageTooLargeForContextException if the most recent message alone exceeds the
     *     effective context window, or if no user turn fits in it
     * @throws PromptDoesNotFitContextException if the system prompt leaves no room for the most
     *     recent message
     */
    public LlmInvocationContext build(List<Message> conversationMessages, String modelName,
                                      String systemPrompt) {
        return build(conversationMessages, modelName, systemPrompt, Set.of());
    }

    /**
     * {@link #build(List, String, String)}, eliding past lookups (deferred R1): before the latest
     * USER message — in earlier turns — the result of every call to a tool in {@code elidedLookups}
     * is replaced by {@link #ELIDED_RESULT}. The call stays, and so does its paired result, as every
     * provider requires; only its content goes. The current turn is never elided: the loop is still
     * working on it.
     */
    public LlmInvocationContext build(List<Message> conversationMessages, String modelName,
                                      String systemPrompt, Set<String> elidedLookups) {
        return build(conversationMessages, modelName, ModelContextWindow.contextWindowFor(modelName),
                systemPrompt, List.of(), elidedLookups);
    }

    /**
     * {@link #build(List, String, String, Set)} for a window {@code contextWindow} tokens wide,
     * given rather than looked up by model — the window the provider actually serves, when it is
     * the provider's setting (a local server's context length) and not the model's — and with the
     * {@code tools} offered to the model, whose schemas count against it as the prompt does.
     *
     * @throws PromptDoesNotFitContextException if the system prompt and the tools leave no room
     *     for the most recent message
     */
    public LlmInvocationContext build(List<Message> conversationMessages, String modelName,
                                      int contextWindow, String systemPrompt, List<ToolDefinition> tools,
                                      Set<String> elidedLookups) {
        if (!elidedLookups.isEmpty()) {
            conversationMessages = elidePastLookups(conversationMessages, elidedLookups);
        }
        int reservedForResponse = reservedForResponse(contextWindow);
        int historyWindow = contextWindow - reservedForResponse;
        int fixedTokens = fixedTokens(systemPrompt, tools);
        int effectiveWindow = historyWindow - fixedTokens;

        List<Message> selected = new ArrayList<>();
        int runningTokens = 0;
        int lastIndex = conversationMessages.size() - 1;

        for (int i = lastIndex; i >= 0; i--) {
            Message message = conversationMessages.get(i);
            if (message.role() == MessageRole.FALLBACK) {
                continue; // the platform's reply to a failure, not the agent's turn (ADR 0005, C3)
            }
            int messageTokens = estimateTokens(message);

            if (i == lastIndex && messageTokens > effectiveWindow) {
                if (fixedTokens < historyWindow && messageTokens > historyWindow) {
                    // Too large for the window with no prompt at all: the message, not the agent.
                    throw new MessageTooLargeForContextException(
                            "Most recent message estimated at " + messageTokens
                                    + " tokens exceeds the effective context window of "
                                    + historyWindow + " tokens for model '" + modelName + "'");
                }
                throw new PromptDoesNotFitContextException("The system prompt and tools do not fit the"
                        + " context window of " + contextWindow + " tokens: they are estimated at "
                        + fixedTokens + " tokens (" + tools.size() + " tools), the current message at "
                        + messageTokens + ", and " + reservedForResponse + " are reserved for the"
                        + " response, for model '" + modelName + "'. Shorten the prompt, grant fewer"
                        + " tools, or serve a larger window");
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
                window, systemPrompt, runningTokens, fixedTokens, contextWindow, reservedForResponse);
    }

    /**
     * The estimate of what every request carries besides the history: the system prompt, and each
     * tool's name, description and input schema plus the provider's tool-use overhead.
     */
    static int fixedTokens(String systemPrompt, List<ToolDefinition> tools) {
        int tokens = TokenEstimator.estimate(systemPrompt);
        if (!tools.isEmpty()) {
            tokens += TOOL_USE_OVERHEAD;
        }
        for (ToolDefinition tool : tools) {
            tokens += PER_TOOL_OVERHEAD + TokenEstimator.estimate(tool.name())
                    + TokenEstimator.estimate(tool.description()) + TokenEstimator.estimateJson(tool.inputSchema());
        }
        return tokens;
    }

    /** {@link #RESERVED_FOR_RESPONSE}, or half the window when that would leave under {@link #MIN_HISTORY}. */
    static int reservedForResponse(int contextWindow) {
        return contextWindow - RESERVED_FOR_RESPONSE >= MIN_HISTORY ? RESERVED_FOR_RESPONSE : contextWindow / 2;
    }

    private static List<Message> elidePastLookups(List<Message> messages, Set<String> elidedLookups) {
        int currentTurn = messages.size();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == MessageRole.USER) {
                currentTurn = i;
                break;
            }
        }
        List<Message> view = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (i < currentTurn && message.toolContent().orElse(null) instanceof ToolResult result
                    && elidedLookups.contains(result.toolName())) {
                ToolResult elided = new ToolResult(result.toolCallId(), result.toolName(), ELIDED_RESULT, false);
                view.add(Message.rehydrate(message.id(), message.conversationId(), MessageRole.TOOL_RESULT,
                        ELIDED_RESULT, elided, message.createdAt()));
            } else {
                view.add(message);
            }
        }
        return view;
    }

    /**
     * Estimates the wire-token cost of a message. Text content is measured directly; a
     * TOOL_CALL additionally carries its input arguments on the wire (the rendered
     * {@code content()} is only the tool name), so they are added. A TOOL_RESULT's output is
     * already the message content, so no extra is added.
     */
    private static int estimateTokens(Message message) {
        int tokens = TokenEstimator.estimate(modelText(message));
        if (message.toolContent().orElse(null) instanceof ToolCall call) {
            tokens += TokenEstimator.estimate(String.valueOf(call.input()));
        }
        return tokens;
    }

    /**
     * The text the model reads for {@code message}: its content, except that a voice note reads as
     * its transcript marked {@link #TRANSCRIBED_VOICE_NOTE}.
     */
    static String modelText(Message message) {
        if (message.parts().stream().noneMatch(MessagePart.Audio.class::isInstance)) {
            return message.content();
        }
        return message.parts().stream().map(part -> switch (part) {
            case MessagePart.Audio audio -> TRANSCRIBED_VOICE_NOTE + audio.transcriptText().orElse(Message.UNTRANSCRIBED_AUDIO);
            case MessagePart.Text text -> text.text();
            case MessagePart.ToolCallPart call -> call.call().toolName();
            case MessagePart.ToolResultPart result -> result.result().output();
        }).collect(Collectors.joining("\n"));
    }

    /**
     * Translates a domain {@link Message} to a neutral {@link LlmMessage}. Text roles map to
     * their {@link LlmRole}; tool roles use the neutral tool factories, carrying the structured
     * {@code toolContent} so the adapters can assemble each provider's tool wire format.
     */
    private static LlmMessage toLlmMessage(Message message) {
        return switch (message.role()) {
            case USER -> new LlmMessage(LlmRole.USER, modelText(message));
            case AGENT -> new LlmMessage(LlmRole.ASSISTANT, message.content());
            case SYSTEM -> new LlmMessage(LlmRole.SYSTEM, message.content());
            case TOOL_CALL -> LlmMessage.toolCall((ToolCall) message.toolContent().orElseThrow());
            case TOOL_RESULT -> LlmMessage.toolResult((ToolResult) message.toolContent().orElseThrow());
            case FALLBACK -> throw new IllegalStateException("FALLBACK messages never reach the model");
        };
    }
}
