package dev.cauce.orchestration.service;

import dev.cauce.llm.model.FinishReason;
import dev.cauce.llm.model.LlmInvocation;
import dev.cauce.llm.model.LlmResponse;
import dev.cauce.llm.model.LlmUsage;
import dev.cauce.llm.spi.LlmProvider;
import java.util.List;
import java.util.function.Function;

/**
 * Test double standing in for the Anthropic adapter (registers under id {@code "anthropic"})
 * so integration tests never make a real network call. The response is configurable per test
 * via {@link #respondWith}, and the last received invocation is captured for assertions.
 */
public class MockLlmProvider implements LlmProvider {

    private volatile Function<LlmInvocation, LlmResponse> responder = invocation ->
            new LlmResponse("default reply", List.of(), FinishReason.STOP, LlmUsage.of(1, 1));
    private volatile LlmInvocation lastInvocation;

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
        this.lastInvocation = invocation;
        return responder.apply(invocation);
    }

    void respondWith(Function<LlmInvocation, LlmResponse> responder) {
        this.responder = responder;
    }

    LlmInvocation lastInvocation() {
        return lastInvocation;
    }
}
