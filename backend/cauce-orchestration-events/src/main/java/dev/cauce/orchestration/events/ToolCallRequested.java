package dev.cauce.orchestration.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The model asked to run a tool. Emitted after the TOOL_CALL message has been persisted
 * (its own committed transaction), before the tool executes.
 *
 * @param invocationId the invocation being processed
 * @param roundIndex zero-based loop round the call was requested in
 * @param toolName the stable name of the requested tool
 * @param toolCallId the provider correlation id linking this call to its result
 * @param occurredAt when the request was recorded
 */
public record ToolCallRequested(
        UUID invocationId,
        int roundIndex,
        String toolName,
        String toolCallId,
        Instant occurredAt) implements OrchestrationEvent {

    public ToolCallRequested {
        Objects.requireNonNull(invocationId, "invocationId");
        EventPreconditions.requireNonNegative(roundIndex, "roundIndex");
        EventPreconditions.requireText(toolName, "toolName");
        EventPreconditions.requireText(toolCallId, "toolCallId");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
