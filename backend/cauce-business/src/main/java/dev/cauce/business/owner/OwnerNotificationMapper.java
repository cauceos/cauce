package dev.cauce.business.owner;

import org.springframework.stereotype.Component;

/** Hand-written mapper between {@link OwnerNotification} and {@link OwnerNotificationEntity}. */
@Component
public class OwnerNotificationMapper {

    public OwnerNotificationEntity toEntity(OwnerNotification n) {
        return new OwnerNotificationEntity(n.id(), n.tenantId(), n.kind(), n.subjectId(), n.content(), n.status(),
                n.reason(), n.attempts(), n.nextAttemptAt(), n.sentAt(), n.createdAt(), n.updatedAt());
    }

    public OwnerNotification toDomain(OwnerNotificationEntity e) {
        return new OwnerNotification(e.getId(), e.getTenantId(), e.getKind(), e.getSubjectId(), e.getContent(),
                e.getStatus(), e.getReason(), e.getAttempts(), e.getNextAttemptAt(), e.getSentAt(), e.getCreatedAt(),
                e.getUpdatedAt());
    }
}
