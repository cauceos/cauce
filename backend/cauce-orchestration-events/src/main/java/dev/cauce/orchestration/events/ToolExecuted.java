package dev.cauce.orchestration.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A requested tool ran and its result was persisted (its own committed transaction).
 * A tool-level failure (unknown tool, thrown exception, rejected input) does not fail the
 * invocation — it surfaces here as {@code isError} and is fed back to the model.
 *
 * @param invocationId the invocation being processed
 * @param roundIndex zero-based loop round the tool ran in
 * @param toolName the stable name of the executed tool
 * @param toolCallId the provider correlation id linking the result to its call
 * @param isError whether the result is an error payload
 * @param durationMs wall-clock duration of the tool execution itself (dispatch only,
 *     persistence excluded)
 * @param occurredAt when the result was recorded
 */
public record ToolExecuted(
        UUID invocationId,
        int roundIndex,
        String toolName,
        String toolCallId,
        boolean isError,
        long durationMs,
        Instant occurredAt) implements OrchestrationEvent {

    public ToolExecuted {
        Objects.requireNonNull(invocationId, "invocationId");
        EventPreconditions.requireNonNegative(roundIndex, "roundIndex");
        EventPreconditions.requireText(toolName, "toolName");
        EventPreconditions.requireText(toolCallId, "toolCallId");
        EventPreconditions.requireNonNegative(durationMs, "durationMs");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
