package dev.cauce.evals.run;

import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolResult;
import dev.cauce.llm.model.FinishReason;
import dev.cauce.llm.model.LlmInvocation;
import dev.cauce.llm.model.LlmMessage;
import dev.cauce.llm.model.LlmResponse;
import dev.cauce.llm.model.LlmUsage;
import dev.cauce.llm.spi.LlmProvider;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The model of a rehearsal ({@code -Pevals.simulacro=true}): no tokens, no network. Each turn it
 * searches the menu once and then answers a fixed text, so the run exercises the whole plumbing —
 * the business set up, the previo seeded, a tool call traced and audited, the records read, the
 * report written — before a real run spends anything. Its results say nothing about the agent.
 */
final class RehearsalProvider implements LlmProvider {

    static final String REPLY = "Simulacro: respuesta fija, sin modelo.";

    private final String id;

    RehearsalProvider(String id) {
        this.id = id;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public LlmResponse invoke(LlmInvocation invocation) {
        LlmMessage last = invocation.messages().get(invocation.messages().size() - 1);
        boolean answeredATool = last.toolContent() instanceof ToolResult;
        if (!answeredATool && invocation.tools().stream().anyMatch(t -> t.name().equals("search_catalog"))) {
            return new LlmResponse("", List.of(new ToolCall("rehearsal-" + UUID.randomUUID(), "search_catalog", Map.of())),
                    FinishReason.TOOL_USE, LlmUsage.of(1, 1));
        }
        return new LlmResponse(REPLY, List.of(), FinishReason.STOP, LlmUsage.of(1, 1));
    }

    @Override
    public boolean supports(String modelName) {
        return true;
    }
}
