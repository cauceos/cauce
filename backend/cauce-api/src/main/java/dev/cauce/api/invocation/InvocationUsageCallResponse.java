package dev.cauce.api.invocation;

import dev.cauce.orchestration.usage.LlmUsageRecord;
import java.time.Instant;

/**
 * One provider call inside an invocation, as the ledger recorded it.
 *
 * <p>{@code roundIndex} is an attribute of the call, never its identity: a worker retry
 * re-runs the loop from round zero, so the same index can appear more than once in one
 * invocation. The list order — oldest first — is what tells the calls apart.
 *
 * <p>The internal row id is not exposed: it addresses nothing a client can ask for, and the
 * ledger's own query surface is a separate unit.
 *
 * @param roundIndex   which agentic round this call was, within its attempt
 * @param provider     provider id, verbatim from the cauce-llm SPI vocabulary
 * @param model        the model as the agent had it configured when the call was made
 * @param finishReason why the provider stopped, verbatim from the SPI vocabulary
 */
public record InvocationUsageCallResponse(int roundIndex,
                                          String provider,
                                          String model,
                                          int inputTokens,
                                          int outputTokens,
                                          int totalTokens,
                                          String finishReason,
                                          Instant recordedAt) {

    public static InvocationUsageCallResponse from(LlmUsageRecord record) {
        return new InvocationUsageCallResponse(
                record.roundIndex(),
                record.provider(),
                record.model(),
                record.inputTokens(),
                record.outputTokens(),
                record.totalTokens(),
                record.finishReason(),
                record.createdAt());
    }
}
