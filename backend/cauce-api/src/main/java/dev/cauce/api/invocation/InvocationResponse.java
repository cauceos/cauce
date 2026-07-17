package dev.cauce.api.invocation;

import dev.cauce.orchestration.PendingInvocation;
import java.time.Instant;
import java.util.UUID;

/**
 * Public view of an invocation's processing state. Deliberately minimal: retry mechanics
 * ({@code attempt_count}, backoff, claim ownership) and the stored error detail
 * ({@code last_error}, which may carry raw provider messages) are internal and never
 * exposed. {@code failureReason} is set only for permanently failed invocations, and may be
 * null even then for failures recorded before the taxonomy was persisted on the row.
 */
public record InvocationResponse(UUID id, UUID conversationId, UUID triggerMessageId,
                                 InvocationStatus status, FailureReason failureReason,
                                 Instant createdAt, Instant completedAt) {

    public static InvocationResponse from(PendingInvocation invocation) {
        return new InvocationResponse(
                invocation.id(),
                invocation.conversationId(),
                invocation.triggerMessageId(),
                InvocationStatus.from(invocation.status()),
                invocation.failureType() == null ? null : FailureReason.from(invocation.failureType()),
                invocation.createdAt(),
                invocation.completedAt());
    }
}
