package dev.cauce.api.business;

import dev.cauce.business.digest.DailyDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A nightly summary: the local date it was made on (it reports the next day), what it counted, its
 * text, and whether a notice was queued for the owner.
 */
public record DailyDigestResponse(
        UUID id,
        UUID tenantId,
        LocalDate localDate,
        Instant coveredFrom,
        Instant coveredTo,
        int bookingCount,
        int handoffCount,
        int questionCount,
        String content,
        boolean notified,
        Instant createdAt) {

    public static DailyDigestResponse from(DailyDigest d) {
        return new DailyDigestResponse(d.id(), d.tenantId(), d.localDate(), d.coveredFrom(), d.coveredTo(),
                d.bookingCount(), d.handoffCount(), d.questionCount(), d.content(), d.notified(), d.createdAt());
    }
}
