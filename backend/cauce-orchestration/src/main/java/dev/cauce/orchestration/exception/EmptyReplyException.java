package dev.cauce.orchestration.exception;

/**
 * Thrown when the model ends its turn with a final reply that has no text and no tool calls
 * (ADR 0005, C1). An empty reply is a failure, never a message: nothing empty is persisted as
 * the agent's answer or sent to a channel. Like the iteration cap it is not a provider failure,
 * so the worker fails the invocation after the orchestrator has recorded a SYSTEM error message.
 */
public class EmptyReplyException extends RuntimeException {

    public EmptyReplyException(String message) {
        super(message);
    }
}
