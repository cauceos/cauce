package dev.cauce.api.invocation;

import dev.cauce.orchestration.usage.LlmUsageRecord;
import java.util.List;

/**
 * Token usage recorded for one invocation: the aggregate, and the provider calls it is the
 * sum of.
 *
 * <p><strong>Both, deliberately.</strong> A worker retry re-runs the agentic loop from round
 * zero, so an invocation can hold several calls with the same {@code roundIndex} — each one a
 * real, provider-billed call. The aggregate is therefore the true billed total, but on its own
 * it is unexplainable: only the breakdown shows that the same work was paid for twice.
 *
 * <p>Tokens only. No cost, in any currency: prices change, differ per provider and per model,
 * and a monetary number computed here would be exactly the kind of figure someone later bills
 * from. Cost will be a view over these facts and a versioned price table, never a column.
 *
 * @param calls        every recorded provider call, oldest first; never empty (the whole
 *                     object is absent when nothing was recorded)
 * @param inputTokens  sum of the calls' input tokens
 * @param outputTokens sum of the calls' output tokens
 * @param totalTokens  sum of the calls' total tokens
 * @param complete     whether the recorded calls account for the whole invocation. False when
 *                     the invocation did not end successfully: usage is written only after a
 *                     provider responds, so a round that failed on the way out contributes
 *                     nothing here and the totals are a floor, not the full amount.
 */
public record InvocationUsageResponse(List<InvocationUsageCallResponse> calls,
                                      int inputTokens,
                                      int outputTokens,
                                      int totalTokens,
                                      boolean complete) {

    /**
     * Builds the usage view, or {@code null} when no call was recorded.
     *
     * <p>Null rather than a zeroed object, and the distinction is load-bearing: an invocation
     * that failed before any provider answered did not spend zero tokens — we simply have no
     * record of what it spent. A zero would state a fact the ledger never captured.
     */
    public static InvocationUsageResponse from(List<LlmUsageRecord> records, boolean complete) {
        if (records.isEmpty()) {
            return null;
        }
        int input = 0;
        int output = 0;
        int total = 0;
        for (LlmUsageRecord record : records) {
            input += record.inputTokens();
            output += record.outputTokens();
            total += record.totalTokens();
        }
        return new InvocationUsageResponse(
                records.stream().map(InvocationUsageCallResponse::from).toList(),
                input,
                output,
                total,
                complete);
    }
}
