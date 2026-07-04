package dev.cauce.observability.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.cauce.orchestration.events.ContextAssembled;
import dev.cauce.orchestration.events.InvocationCompleted;
import dev.cauce.orchestration.events.InvocationFailed;
import dev.cauce.orchestration.events.InvocationFailureType;
import dev.cauce.orchestration.events.InvocationRequested;
import dev.cauce.orchestration.events.LlmInvoked;
import dev.cauce.orchestration.events.LlmResponded;
import dev.cauce.orchestration.events.ToolCallRequested;
import dev.cauce.orchestration.events.ToolExecuted;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrchestrationMetricsTest {

    private static final UUID INVOCATION_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-07-04T10:00:00Z");

    private SimpleMeterRegistry registry;
    private OrchestrationMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new OrchestrationMetrics(registry);
    }

    @Test
    void on_invocationRequested_incrementsRequestedCounter() {
        metrics.on(new InvocationRequested(INVOCATION_ID, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), NOW));

        assertThat(registry.counter(OrchestrationMetrics.INVOCATIONS_REQUESTED).count())
                .isEqualTo(1.0);
    }

    @Test
    void on_invocationCompleted_incrementsCompletedCounter() {
        metrics.on(new InvocationCompleted(INVOCATION_ID, 2, UUID.randomUUID(), NOW));

        assertThat(registry.counter(OrchestrationMetrics.INVOCATIONS_COMPLETED).count())
                .isEqualTo(1.0);
    }

    @Test
    void on_invocationFailed_tagsFailureType() {
        metrics.on(new InvocationFailed(INVOCATION_ID, InvocationFailureType.LLM_ERROR,
                "401", NOW));

        assertThat(registry.counter(OrchestrationMetrics.INVOCATIONS_FAILED,
                "failure_type", "LLM_ERROR").count()).isEqualTo(1.0);
    }

    @Test
    void on_llmInvoked_countsCallsByProviderAndModel() {
        metrics.on(new LlmInvoked(INVOCATION_ID, "anthropic", "claude-sonnet-4-7", 0, NOW));

        assertThat(registry.counter(OrchestrationMetrics.LLM_CALLS,
                "provider", "anthropic", "model", "claude-sonnet-4-7").count()).isEqualTo(1.0);
    }

    @Test
    void on_llmResponded_countsResponsesByFinishReason() {
        metrics.on(new LlmResponded(INVOCATION_ID, "anthropic", "claude-sonnet-4-7", 0,
                "STOP", 7, 9, 16, NOW));

        assertThat(registry.counter(OrchestrationMetrics.LLM_RESPONSES,
                "provider", "anthropic", "model", "claude-sonnet-4-7",
                "finish_reason", "STOP").count()).isEqualTo(1.0);
    }

    @Test
    void on_llmResponded_incrementsTokenCountersByUsage() {
        metrics.on(new LlmResponded(INVOCATION_ID, "anthropic", "claude-sonnet-4-7", 0,
                "STOP", 7, 9, 16, NOW));

        assertThat(tokenCount("input")).isEqualTo(7.0);
        assertThat(tokenCount("output")).isEqualTo(9.0);
        assertThat(tokenCount("total")).isEqualTo(16.0);
    }

    @Test
    void on_toolCallRequested_countsCallsByTool() {
        metrics.on(new ToolCallRequested(INVOCATION_ID, 0, "get_current_time", "call-1", NOW));

        assertThat(registry.counter(OrchestrationMetrics.TOOL_CALLS,
                "tool", "get_current_time").count()).isEqualTo(1.0);
    }

    @Test
    void on_toolExecuted_recordsTimerWithOutcomeTag() {
        metrics.on(new ToolExecuted(INVOCATION_ID, 0, "get_current_time", "call-1",
                false, 42L, NOW));

        Timer timer = registry.timer(OrchestrationMetrics.TOOL_EXECUTION,
                "tool", "get_current_time", "outcome", "success");
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(42.0);
    }

    @Test
    void on_toolExecuted_withError_tagsOutcomeError() {
        metrics.on(new ToolExecuted(INVOCATION_ID, 1, "get_current_time", "call-2",
                true, 5L, NOW));

        assertThat(registry.timer(OrchestrationMetrics.TOOL_EXECUTION,
                "tool", "get_current_time", "outcome", "error").count()).isEqualTo(1);
    }

    @Test
    void on_contextAssembled_registersNothing() {
        metrics.on(new ContextAssembled(INVOCATION_ID, 0, "claude-sonnet-4-7", 3, 1200,
                200000, NOW));

        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void on_whenRegistryThrows_swallowsAndLogs() {
        MeterRegistry throwing = mock(MeterRegistry.class);
        when(throwing.counter(any(String.class), any(String[].class)))
                .thenThrow(new IllegalStateException("registry closed"));
        OrchestrationMetrics guarded = new OrchestrationMetrics(throwing);

        assertThatCode(() -> guarded.on(new InvocationRequested(INVOCATION_ID,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), NOW)))
                .doesNotThrowAnyException();
    }

    private double tokenCount(String kind) {
        return registry.counter(OrchestrationMetrics.LLM_TOKENS,
                "provider", "anthropic", "model", "claude-sonnet-4-7", "kind", kind).count();
    }
}
