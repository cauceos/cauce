package dev.cauce.orchestration.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrchestrationEventTest {

    private static final UUID ID = UUID.randomUUID();
    private static final Instant TS = Instant.parse("2026-07-03T10:00:00Z");

    @Test
    void invocationRequested_nullField_throws() {
        assertThatThrownBy(() -> new InvocationRequested(null, ID, ID, ID, ID, TS))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("invocationId");
        assertThatThrownBy(() -> new InvocationRequested(ID, null, ID, ID, ID, TS))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("tenantId");
        assertThatThrownBy(() -> new InvocationRequested(ID, ID, ID, ID, ID, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("occurredAt");
    }

    @Test
    void contextAssembled_invalidPayload_throws() {
        assertThatThrownBy(() -> new ContextAssembled(ID, -1, "m", 1, 1, 1, TS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("roundIndex");
        assertThatThrownBy(() -> new ContextAssembled(ID, 0, " ", 1, 1, 1, TS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("modelName");
        assertThatThrownBy(() -> new ContextAssembled(ID, 0, "m", -1, 1, 1, TS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("messageCount");
    }

    @Test
    void llmInvoked_blankProvider_throws() {
        assertThatThrownBy(() -> new LlmInvoked(ID, "", "m", 0, TS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("provider");
    }

    @Test
    void llmResponded_invalidPayload_throws() {
        assertThatThrownBy(() -> new LlmResponded(ID, "p", "m", 0, null, 1, 1, 2, TS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("finishReason");
        assertThatThrownBy(() -> new LlmResponded(ID, "p", "m", 0, "STOP", -1, 1, 0, TS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("inputTokens");
    }

    @Test
    void toolCallRequested_blankToolName_throws() {
        assertThatThrownBy(() -> new ToolCallRequested(ID, 0, " ", "call-1", TS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("toolName");
    }

    @Test
    void toolExecuted_negativeDuration_throws() {
        assertThatThrownBy(() -> new ToolExecuted(ID, 0, "t", "call-1", false, -5, TS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("durationMs");
    }

    @Test
    void invocationCompleted_nullFinalMessageId_throws() {
        assertThatThrownBy(() -> new InvocationCompleted(ID, ID, ID, 1, null, null, TS))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("finalMessageId");
    }

    @Test
    void invocationFailed_invalidPayload_throws() {
        assertThatThrownBy(() -> new InvocationFailed(ID, null, "boom", TS))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("failureType");
        assertThatThrownBy(() ->
                new InvocationFailed(ID, InvocationFailureType.LLM_ERROR, " ", TS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("detail");
    }

    @Test
    void allEvents_validPayload_exposeInvocationIdAndOccurredAt() {
        for (OrchestrationEvent event : new OrchestrationEvent[] {
                new InvocationRequested(ID, ID, ID, ID, ID, TS),
                new ContextAssembled(ID, 0, "m", 1, 10, 200_000, TS),
                new LlmInvoked(ID, "anthropic", "m", 0, TS),
                new LlmFailed(ID, "anthropic", "m", 0, "LlmProviderUnavailableException", TS),
                new LlmResponded(ID, "anthropic", "m", 0, "STOP", 5, 7, 12, TS),
                new ToolCallRequested(ID, 0, "get_current_time", "call-1", TS),
                new ToolExecuted(ID, 0, "get_current_time", "call-1", false, 3, TS),
                new InvocationCompleted(ID, ID, ID, 1, ID, java.time.Duration.ofSeconds(4), TS),
                new InvocationFailed(ID, InvocationFailureType.SETUP_ERROR, "boom", TS),
                new FallbackSent(ID, ID, ID, InvocationFailureType.LLM_ERROR, TS)}) {
            assertThat(event.invocationId()).isEqualTo(ID);
            assertThat(event.occurredAt()).isEqualTo(TS);
        }
    }

    @Test
    void invocationRequested_withoutAPreviousMessage_hasNoGap() {
        assertThat(new InvocationRequested(ID, ID, ID, ID, ID, TS).sincePreviousMessage()).isNull();
        assertThat(new InvocationRequested(ID, ID, ID, ID, ID, TS, java.time.Duration.ofMinutes(3))
                .sincePreviousMessage()).isEqualTo(java.time.Duration.ofMinutes(3));
    }
}
