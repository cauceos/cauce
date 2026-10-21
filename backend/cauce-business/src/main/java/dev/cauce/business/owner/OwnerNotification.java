package dev.cauce.business.owner;

import dev.cauce.core.UuidGenerator;
import dev.cauce.core.business.owner.OwnerNotifier.Delivery;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A proactive message to the owner of a business (ADR 0007, B9), kept in an outbox: written in the
 * transaction of the fact it reports, sent later by the dispatcher, and kept with what happened to
 * it. NOT_SENT and FAILED are final and say why; the record stays readable whatever the channel did.
 *
 * @param subjectId the handoff or the digest the notice reports
 */
public record OwnerNotification(UUID id, UUID tenantId, Kind kind, UUID subjectId, String content, Status status,
                                String reason, int attempts, Instant nextAttemptAt, Instant sentAt,
                                Instant createdAt, Instant updatedAt) {

    /** What the notice reports. */
    public enum Kind {
        HANDOFF,
        DAILY_DIGEST
    }

    /** Where the notice is. */
    public enum Status {
        PENDING,
        SENT,
        NOT_SENT,
        FAILED
    }

    /** Why a notice was never attempted: no owner is linked to the business. */
    public static final String NO_OWNER_RECIPIENT = "no_owner_recipient";

    public OwnerNotification {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /**
     * A notice due at {@code now}, a reading of the database clock (ADR 0008): the dispatcher's claim
     * compares it with the database's {@code now()}. NOT_SENT at once when there is no owner to send
     * it to.
     */
    public static OwnerNotification queue(UUID tenantId, Kind kind, UUID subjectId, String content,
                                          boolean ownerLinked, Instant now) {
        Objects.requireNonNull(now, "now");
        return new OwnerNotification(UuidGenerator.newV7(), tenantId, kind, subjectId, content,
                ownerLinked ? Status.PENDING : Status.NOT_SENT, ownerLinked ? null : NO_OWNER_RECIPIENT, 0, now,
                null, now, now);
    }

    /**
     * This notice after an attempt that ended in {@code delivery}: SENT; NOT_SENT for good; or, on a
     * FAILED send, still PENDING for the next lease unless {@code attempts} reached
     * {@code maxAttempts}, then FAILED. A retry is due when the lease its claim took ends.
     */
    public OwnerNotification after(Delivery delivery, int maxAttempts, Instant now) {
        return switch (delivery.outcome()) {
            case SENT -> with(Status.SENT, null, now, now);
            case NOT_SENT -> with(Status.NOT_SENT, delivery.reason(), null, now);
            case FAILED -> attempts >= maxAttempts
                    ? with(Status.FAILED, delivery.reason(), null, now)
                    : with(Status.PENDING, delivery.reason(), null, now);
        };
    }

    /** This notice closed as NOT_SENT with {@code reason} at {@code now}. */
    public OwnerNotification notSent(String reason, Instant now) {
        return with(Status.NOT_SENT, reason, null, now);
    }

    private OwnerNotification with(Status newStatus, String newReason, Instant newSentAt, Instant now) {
        Objects.requireNonNull(now, "now");
        String bounded = newReason == null ? null : newReason.substring(0, Math.min(200, newReason.length()));
        return new OwnerNotification(id, tenantId, kind, subjectId, content, newStatus, bounded, attempts,
                nextAttemptAt, newSentAt, createdAt, now);
    }

    public Optional<String> reasonIfAny() {
        return Optional.ofNullable(reason);
    }
}
