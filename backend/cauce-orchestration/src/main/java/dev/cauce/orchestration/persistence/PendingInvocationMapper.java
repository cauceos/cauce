package dev.cauce.orchestration.persistence;

import dev.cauce.orchestration.PendingInvocation;
import org.springframework.stereotype.Component;

/**
 * Hand-written bidirectional mapping between the domain {@link PendingInvocation} and its
 * JPA {@link PendingInvocationEntity}. No external mapping library is used.
 */
@Component
public final class PendingInvocationMapper {

    public PendingInvocationEntity toEntity(PendingInvocation invocation) {
        return new PendingInvocationEntity(
                invocation.id(),
                invocation.tenantId(),
                invocation.conversationId(),
                invocation.triggerMessageId(),
                invocation.status(),
                invocation.attemptCount(),
                invocation.maxAttempts(),
                invocation.lastAttemptAt(),
                invocation.lastError(),
                invocation.createdAt(),
                invocation.claimedAt(),
                invocation.claimedBy(),
                invocation.completedAt(),
                invocation.nextAttemptAt());
    }

    public PendingInvocation toDomain(PendingInvocationEntity entity) {
        return PendingInvocation.rehydrate(
                entity.getId(),
                entity.getTenantId(),
                entity.getConversationId(),
                entity.getTriggerMessageId(),
                entity.getStatus(),
                entity.getAttemptCount(),
                entity.getMaxAttempts(),
                entity.getLastAttemptAt(),
                entity.getLastError(),
                entity.getCreatedAt(),
                entity.getClaimedAt(),
                entity.getClaimedBy(),
                entity.getCompletedAt(),
                entity.getNextAttemptAt());
    }
}
