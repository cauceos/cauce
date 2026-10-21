package dev.cauce.api.business;

import dev.cauce.business.owner.OwnerNotification;
import java.time.Instant;
import java.util.UUID;

/**
 * A notice to the owner and what happened to it: SENT, NOT_SENT with why (outside the reply window,
 * no owner linked, the channel unavailable), FAILED after its attempts, or still PENDING.
 */
public record OwnerNotificationResponse(
        UUID id,
        UUID tenantId,
        String kind,
        UUID subjectId,
        String content,
        String status,
        String reason,
        int attempts,
        Instant sentAt,
        Instant createdAt) {

    public static OwnerNotificationResponse from(OwnerNotification n) {
        return new OwnerNotificationResponse(n.id(), n.tenantId(), n.kind().name(), n.subjectId(), n.content(),
                n.status().name(), n.reason(), n.attempts(), n.sentAt(), n.createdAt());
    }
}
