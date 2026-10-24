package dev.cauce.api.support;

import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolResult;
import dev.cauce.llm.model.FinishReason;
import dev.cauce.llm.model.LlmInvocation;
import dev.cauce.llm.model.LlmMessage;
import dev.cauce.llm.model.LlmResponse;
import dev.cauce.llm.model.LlmUsage;
import dev.cauce.llm.spi.LlmProvider;
import java.util.List;
import java.util.OptionalInt;
import java.util.Queue;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Test double standing in for an Anthropic-style LLM adapter so the cauce-api end-to-end
 * messaging test never makes a network call. Registers under id {@code "anthropic"} and
 * supports {@code claude-*} models, matching an agent created with provider {@code "anthropic"}.
 * The real adapter is disabled in that test ({@code cauce.llm.anthropic.enabled=false}), so this
 * is the only provider in the registry and there is no id collision. Returns a fixed reply,
 * unless a failure has been armed with {@link #failNextWith}, or a tool round with
 * {@link #useToolOnFirstCall} — the provider bean is cached in the shared application context
 * across IT classes, so tests that arm either must {@link #reset()} it (e.g. in an
 * {@code @AfterEach}).
 */
public class FakeLlmProvider implements LlmProvider {

    /** The canned reply text, asserted by the messaging IT. */
    public static final String REPLY = "Hola, soy un agente de prueba";

    /** Token counts of the plain reply — one call, two tokens. */
    public static final int REPLY_INPUT_TOKENS = 1;
    public static final int REPLY_OUTPUT_TOKENS = 1;

    /** Token counts of the tool-use round, distinct so a sum cannot pass by coincidence. */
    public static final int TOOL_INPUT_TOKENS = 5;
    public static final int TOOL_OUTPUT_TOKENS = 3;

    private volatile RuntimeException nextFailure;
    private volatile boolean useTool;
    private volatile OptionalInt servedContextWindow = OptionalInt.empty();
    private final AtomicInteger calls = new AtomicInteger();
    private final Queue<Function<LlmInvocation, LlmResponse>> scripted = new ConcurrentLinkedQueue<>();
    private final List<LlmInvocation> invocations = new CopyOnWriteArrayList<>();

    @Override
    public String id() {
        return "anthropic";
    }

    @Override
    public boolean supports(String modelName) {
        return modelName != null && modelName.startsWith("claude-");
    }

    @Override
    public OptionalInt servedContextWindow(String modelName) {
        return servedContextWindow;
    }

    /** Plays a server whose context length is its own setting (Ollama's), until {@link #reset()}. */
    public void serveContextWindow(int tokens) {
        this.servedContextWindow = OptionalInt.of(tokens);
    }

    @Override
    public LlmResponse invoke(LlmInvocation invocation) {
        invocations.add(invocation);
        Function<LlmInvocation, LlmResponse> next = scripted.poll();
        if (next != null) {
            return next.apply(invocation);
        }
        RuntimeException failure = nextFailure;
        if (failure != null) {
            throw failure;
        }
        // Round 0 asks for the built-in clock tool; the orchestrator executes it, feeds the
        // result back, and calls again — that second call falls through to the plain reply.
        if (useTool && calls.getAndIncrement() == 0) {
            return new LlmResponse(
                    "",
                    List.of(new ToolCall(UUID.randomUUID().toString(), "get_current_time", Map.of())),
                    FinishReason.TOOL_USE,
                    LlmUsage.of(TOOL_INPUT_TOKENS, TOOL_OUTPUT_TOKENS));
        }
        return new LlmResponse(REPLY, List.of(), FinishReason.STOP,
                LlmUsage.of(REPLY_INPUT_TOKENS, REPLY_OUTPUT_TOKENS));
    }

    /** Arms the provider to throw {@code failure} on every invoke until {@link #reset()}. */
    public void failNextWith(RuntimeException failure) {
        this.nextFailure = failure;
    }

    /**
     * Arms a two-round agentic loop: the first invoke requests {@code get_current_time},
     * every later one returns the plain reply.
     */
    public void useToolOnFirstCall() {
        this.useTool = true;
        this.calls.set(0);
    }

    /**
     * Scripts the next responses, returned one per invoke in order before any other behaviour: how
     * a test plays a model that calls given tools with given arguments, then replies.
     */
    public void script(LlmResponse... responses) {
        for (LlmResponse response : responses) {
            scripted.add(invocation -> response);
        }
    }

    /**
     * Scripts the next response as a function of the invocation, for a model that reacts to what a
     * tool just returned (its last message is that tool's result).
     */
    public void scriptReacting(Function<LlmInvocation, LlmResponse> response) {
        scripted.add(response);
    }

    /** The output of the last message of {@code invocation}, which must be a tool result. */
    public static String lastToolOutput(LlmInvocation invocation) {
        LlmMessage last = invocation.messages().get(invocation.messages().size() - 1);
        return ((ToolResult) last.toolContent()).output();
    }

    /** A scripted round asking for one tool call. */
    public static LlmResponse toolCall(String toolName, Map<String, Object> input) {
        return new LlmResponse("", List.of(new ToolCall(UUID.randomUUID().toString(), toolName, input)),
                FinishReason.TOOL_USE, LlmUsage.of(TOOL_INPUT_TOKENS, TOOL_OUTPUT_TOKENS));
    }

    /** A scripted final reply. */
    public static LlmResponse finalReply(String text) {
        return new LlmResponse(text, List.of(), FinishReason.STOP, LlmUsage.of(REPLY_INPUT_TOKENS, REPLY_OUTPUT_TOKENS));
    }

    /** Every invocation received since the last {@link #reset()}, in order. */
    public List<LlmInvocation> invocations() {
        return List.copyOf(invocations);
    }

    /** Restores the canned-reply behaviour. */
    public void reset() {
        this.scripted.clear();
        this.invocations.clear();
        this.nextFailure = null;
        this.useTool = false;
        this.servedContextWindow = OptionalInt.empty();
        this.calls.set(0);
    }
}
