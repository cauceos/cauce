package dev.cauce.orchestration.events;

import java.time.Instant;
import java.util.UUID;

/**
 * An observable step in the life of one queued LLM invocation, from enqueue
 * ({@link InvocationRequested}) through the agentic loop's rounds ({@link ContextAssembled},
 * {@link LlmInvoked}, {@link LlmResponded}, {@link ToolCallRequested}, {@link ToolExecuted})
 * to a terminal outcome ({@link InvocationCompleted} or {@link InvocationFailed}).
 *
 * <p>Events are emitted synchronously via Spring's {@code ApplicationEventPublisher} at the
 * point where the step's effects are already durable (each loop round commits through short
 * transactions before its events fire); the one exception, {@link InvocationRequested}, is
 * published inside the ingest transaction — see its javadoc. There are no consumers yet;
 * this contract is the hook for observability, governance, and usage accounting.
 */
public sealed interface OrchestrationEvent
        permits InvocationRequested, ContextAssembled, LlmInvoked, LlmResponded,
        ToolCallRequested, ToolExecuted, InvocationCompleted, InvocationFailed {

    /** The id of the queued invocation ({@code pending_invocations} row) this event belongs to. */
    UUID invocationId();

    /** When the step happened. */
    Instant occurredAt();
}
