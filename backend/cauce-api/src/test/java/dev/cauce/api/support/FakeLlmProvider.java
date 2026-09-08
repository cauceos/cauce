package dev.cauce.api.support;

import dev.cauce.core.tool.ToolCall;
import dev.cauce.llm.model.FinishReason;
import dev.cauce.llm.model.LlmInvocation;
import dev.cauce.llm.model.LlmResponse;
import dev.cauce.llm.model.LlmUsage;
import dev.cauce.llm.spi.LlmProvider;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public String id() {
        return "anthropic";
    }

    @Override
    public boolean supports(String modelName) {
        return modelName != null && modelName.startsWith("claude-");
    }

    @Override
    public LlmResponse invoke(LlmInvocation invocation) {
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

    /** Restores the canned-reply behaviour. */
    public void reset() {
        this.nextFailure = null;
        this.useTool = false;
        this.calls.set(0);
    }
}
