package dev.cauce.api.invocation;

import dev.cauce.orchestration.PendingInvocation;
import dev.cauce.orchestration.usage.LlmUsageRecord;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public view of an invocation's processing state. Deliberately minimal: retry mechanics
 * ({@code attempt_count}, backoff, claim ownership) and the stored error detail
 * ({@code last_error}, which may carry raw provider messages) are internal and never
 * exposed. {@code failureReason} is set only for permanently failed invocations, and may be
 * null even then for failures recorded before the taxonomy was persisted on the row.
 *
 * <p>{@code usage} is additive and nullable: the token facts the LLM ledger recorded for this
 * invocation, or {@code null} when it recorded none. See {@link InvocationUsageResponse} for
 * why absent is not the same as zero.
 */
public record InvocationResponse(UUID id, UUID conversationId, UUID triggerMessageId,
                                 InvocationStatus status, FailureReason failureReason,
                                 Instant createdAt, Instant completedAt,
                                 InvocationUsageResponse usage) {

    public static InvocationResponse from(PendingInvocation invocation,
                                          List<LlmUsageRecord> usageRecords) {
        InvocationStatus status = InvocationStatus.from(invocation.status());
        // The recorded calls account for the whole invocation only when it finished
        // successfully. A failed one may have paid for rounds that completed before the one
        // that broke, and for the round that broke we have nothing — so its totals are a
        // floor, and the flag says so rather than letting a reader assume otherwise.
        boolean complete = status == InvocationStatus.COMPLETED;
        return new InvocationResponse(
                invocation.id(),
                invocation.conversationId(),
                invocation.triggerMessageId(),
                status,
                invocation.failureType() == null ? null : FailureReason.from(invocation.failureType()),
                invocation.createdAt(),
                invocation.completedAt(),
                InvocationUsageResponse.from(usageRecords, complete));
    }
}
