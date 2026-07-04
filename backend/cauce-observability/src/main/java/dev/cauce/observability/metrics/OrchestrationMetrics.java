package dev.cauce.observability.metrics;

import dev.cauce.orchestration.events.ContextAssembled;
import dev.cauce.orchestration.events.InvocationCompleted;
import dev.cauce.orchestration.events.InvocationFailed;
import dev.cauce.orchestration.events.InvocationRequested;
import dev.cauce.orchestration.events.LlmInvoked;
import dev.cauce.orchestration.events.LlmResponded;
import dev.cauce.orchestration.events.OrchestrationEvent;
import dev.cauce.orchestration.events.ToolCallRequested;
import dev.cauce.orchestration.events.ToolExecuted;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The first consumer of the orchestration event stream: translates each
 * {@link OrchestrationEvent} into Micrometer meters. Pure consumer — it only reads event
 * payloads and updates in-memory meters, so it can never influence the business path.
 *
 * <p><strong>Isolation.</strong> Orchestration events are published synchronously on the
 * emitting thread (the ingest transaction, the orchestrator loop, the worker), so an
 * exception escaping this listener would propagate into the business path — inside the
 * ingest it would even roll the transaction back. The whole dispatch is therefore guarded:
 * a failure here is logged at WARN and swallowed; instrumentation must never take
 * production down.
 *
 * <p><strong>Tag cardinality.</strong> Only bounded values are used as tags: provider and
 * model come from validated agent configuration, tool names from the registered tool set,
 * failure types and finish reasons from closed enums. Per-invocation identifiers
 * (invocation, tenant, conversation, tool-call ids) are deliberately NOT tags — they would
 * explode the time series; they belong in logs and future traces.
 *
 * <p>The switch over the sealed interface is exhaustive: adding a ninth event forces a
 * compile-time decision on its metric. {@link ContextAssembled} is an explicit no-op for
 * now (a context-size distribution is deferred).
 */
@Component
public class OrchestrationMetrics {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationMetrics.class);

    static final String INVOCATIONS_REQUESTED = "cauce.orchestration.invocations.requested";
    static final String INVOCATIONS_COMPLETED = "cauce.orchestration.invocations.completed";
    static final String INVOCATIONS_FAILED = "cauce.orchestration.invocations.failed";
    static final String LLM_CALLS = "cauce.orchestration.llm.calls";
    static final String LLM_RESPONSES = "cauce.orchestration.llm.responses";
    static final String LLM_TOKENS = "cauce.orchestration.llm.tokens";
    static final String TOOL_CALLS = "cauce.orchestration.tools.calls";
    static final String TOOL_EXECUTION = "cauce.orchestration.tools.execution";

    private final MeterRegistry registry;

    public OrchestrationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Updates the meters for {@code event}. Never throws: a metrics failure is logged and
     * swallowed so the synchronous publication path stays intact (see class javadoc).
     */
    @EventListener
    public void on(OrchestrationEvent event) {
        try {
            record(event);
        } catch (RuntimeException e) {
            log.warn("Failed to record metrics for {}: {}",
                    event.getClass().getSimpleName(), e.getMessage(), e);
        }
    }

    private void record(OrchestrationEvent event) {
        switch (event) {
            case InvocationRequested requested ->
                    registry.counter(INVOCATIONS_REQUESTED).increment();
            case InvocationCompleted completed ->
                    registry.counter(INVOCATIONS_COMPLETED).increment();
            case InvocationFailed failed ->
                    registry.counter(INVOCATIONS_FAILED,
                            "failure_type", failed.failureType().name()).increment();
            case LlmInvoked invoked ->
                    registry.counter(LLM_CALLS,
                            "provider", invoked.provider(),
                            "model", invoked.modelName()).increment();
            case LlmResponded responded -> {
                registry.counter(LLM_RESPONSES,
                        "provider", responded.provider(),
                        "model", responded.modelName(),
                        "finish_reason", responded.finishReason()).increment();
                tokens(responded, "input", responded.inputTokens());
                tokens(responded, "output", responded.outputTokens());
                tokens(responded, "total", responded.totalTokens());
            }
            case ToolCallRequested toolCall ->
                    registry.counter(TOOL_CALLS, "tool", toolCall.toolName()).increment();
            case ToolExecuted executed ->
                    Timer.builder(TOOL_EXECUTION)
                            .tag("tool", executed.toolName())
                            .tag("outcome", executed.isError() ? "error" : "success")
                            .register(registry)
                            .record(Duration.ofMillis(executed.durationMs()));
            case ContextAssembled ignored -> {
                // No metric yet: a context-size distribution (estimatedTokens vs window) is
                // deferred until context tuning needs it.
            }
        }
    }

    private void tokens(LlmResponded responded, String kind, int amount) {
        registry.counter(LLM_TOKENS,
                "provider", responded.provider(),
                "model", responded.modelName(),
                "kind", kind).increment(amount);
    }
}
