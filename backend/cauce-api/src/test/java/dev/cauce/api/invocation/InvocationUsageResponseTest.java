package dev.cauce.api.invocation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.orchestration.usage.LlmUsageRecord;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the aggregation the public usage view performs — in particular the two
 * cases the ledger's shape makes possible and a naive sum would get wrong: no rows at all,
 * and a repeated {@code roundIndex} after a worker retry.
 */
class InvocationUsageResponseTest {

    @Test
    void from_noRecords_returnsNullRatherThanZeroedTotals() {
        assertThat(InvocationUsageResponse.from(List.of(), false)).isNull();
    }

    @Test
    void from_noRecords_returnsNullEvenWhenTheInvocationCompleted() {
        // Absence is about the ledger, not about the outcome: with no rows there is no
        // number to report either way.
        assertThat(InvocationUsageResponse.from(List.of(), true)).isNull();
    }

    @Test
    void from_singleCall_sumsToThatCall() {
        LlmUsageRecord call = record(0, 10, 4);

        InvocationUsageResponse usage = InvocationUsageResponse.from(List.of(call), true);

        assertThat(usage).isNotNull();
        assertThat(usage.calls()).hasSize(1);
        assertThat(usage.inputTokens()).isEqualTo(10);
        assertThat(usage.outputTokens()).isEqualTo(4);
        assertThat(usage.totalTokens()).isEqualTo(14);
        assertThat(usage.complete()).isTrue();
    }

    @Test
    void from_toolLoop_sumsEveryRoundAndKeepsTheirOrder() {
        List<LlmUsageRecord> calls = List.of(record(0, 10, 4), record(1, 20, 6));

        InvocationUsageResponse usage = InvocationUsageResponse.from(calls, true);

        assertThat(usage).isNotNull();
        assertThat(usage.calls()).extracting(InvocationUsageCallResponse::roundIndex)
                .containsExactly(0, 1);
        assertThat(usage.inputTokens()).isEqualTo(30);
        assertThat(usage.outputTokens()).isEqualTo(10);
        assertThat(usage.totalTokens()).isEqualTo(40);
    }

    @Test
    void from_retryRepeatingARound_countsBothCallsAndShowsTheRepeatedIndex() {
        // A worker retry re-runs the loop from round 0, so the same index appears twice.
        // Each row is one real provider-billed call: the total counts both, and the
        // breakdown is what lets a reader see the same work was paid for twice.
        List<LlmUsageRecord> calls = List.of(record(0, 10, 4), record(0, 10, 4), record(1, 20, 6));

        InvocationUsageResponse usage = InvocationUsageResponse.from(calls, true);

        assertThat(usage).isNotNull();
        assertThat(usage.calls()).hasSize(3);
        assertThat(usage.calls()).extracting(InvocationUsageCallResponse::roundIndex)
                .containsExactly(0, 0, 1);
        assertThat(usage.totalTokens()).isEqualTo(54);
    }

    @Test
    void from_failedInvocation_marksTheTotalsIncomplete() {
        // Rounds that completed before the failure are real spend; the round that broke
        // recorded nothing, so these totals are a floor.
        InvocationUsageResponse usage = InvocationUsageResponse.from(List.of(record(0, 10, 4)), false);

        assertThat(usage).isNotNull();
        assertThat(usage.complete()).isFalse();
        assertThat(usage.totalTokens()).isEqualTo(14);
    }

    @Test
    void call_carriesTheProviderFactsVerbatim() {
        LlmUsageRecord call = record(2, 7, 3);

        InvocationUsageCallResponse view = InvocationUsageCallResponse.from(call);

        assertThat(view.roundIndex()).isEqualTo(2);
        assertThat(view.provider()).isEqualTo("anthropic");
        assertThat(view.model()).isEqualTo("claude-sonnet-4-7");
        assertThat(view.finishReason()).isEqualTo("STOP");
        assertThat(view.recordedAt()).isEqualTo(call.createdAt());
    }

    private static LlmUsageRecord record(int roundIndex, int inputTokens, int outputTokens) {
        return new LlmUsageRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "anthropic", "claude-sonnet-4-7",
                roundIndex, inputTokens, outputTokens, inputTokens + outputTokens, "STOP",
                Instant.now());
    }
}
