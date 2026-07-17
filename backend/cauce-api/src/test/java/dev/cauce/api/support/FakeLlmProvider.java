package dev.cauce.api.support;

import dev.cauce.llm.model.FinishReason;
import dev.cauce.llm.model.LlmInvocation;
import dev.cauce.llm.model.LlmResponse;
import dev.cauce.llm.model.LlmUsage;
import dev.cauce.llm.spi.LlmProvider;
import java.util.List;

/**
 * Test double standing in for an Anthropic-style LLM adapter so the cauce-api end-to-end
 * messaging test never makes a network call. Registers under id {@code "anthropic"} and
 * supports {@code claude-*} models, matching an agent created with provider {@code "anthropic"}.
 * The real adapter is disabled in that test ({@code cauce.llm.anthropic.enabled=false}), so this
 * is the only provider in the registry and there is no id collision. Returns a fixed reply,
 * unless a failure has been armed with {@link #failNextWith} — the provider bean is cached in
 * the shared application context across IT classes, so tests that arm a failure must
 * {@link #reset()} it (e.g. in an {@code @AfterEach}).
 */
public class FakeLlmProvider implements LlmProvider {

    /** The canned reply text, asserted by the messaging IT. */
    public static final String REPLY = "Hola, soy un agente de prueba";

    private RuntimeException nextFailure;

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
        return new LlmResponse(REPLY, List.of(), FinishReason.STOP, LlmUsage.of(1, 1));
    }

    /** Arms the provider to throw {@code failure} on every invoke until {@link #reset()}. */
    public void failNextWith(RuntimeException failure) {
        this.nextFailure = failure;
    }

    /** Restores the canned-reply behaviour. */
    public void reset() {
        this.nextFailure = null;
    }
}
