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
 * is the only provider in the registry and there is no id collision. Returns a fixed reply.
 */
public class FakeLlmProvider implements LlmProvider {

    /** The canned reply text, asserted by the messaging IT. */
    public static final String REPLY = "Hola, soy un agente de prueba";

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
        return new LlmResponse(REPLY, List.of(), FinishReason.STOP, LlmUsage.of(1, 1));
    }
}
