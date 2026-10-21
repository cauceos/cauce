package dev.cauce.business.digest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * The nightly summary of one business for one local date (ADR 0007, B10): what it reported —
 * tomorrow's bookings, the open handoffs, the questions left unanswered in
 * [{@code coveredFrom}, {@code coveredTo}) — and whether a notice was queued for the owner (an empty
 * day of a business that skips empty days is recorded but not sent).
 */
public record DailyDigest(UUID id, UUID tenantId, LocalDate localDate, Instant coveredFrom, Instant coveredTo,
                          int bookingCount, int handoffCount, int questionCount, String content, boolean notified,
                          Instant createdAt) {

    public DailyDigest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(localDate, "localDate");
        Objects.requireNonNull(coveredFrom, "coveredFrom");
        Objects.requireNonNull(coveredTo, "coveredTo");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Whether there was nothing to report. */
    public boolean isEmpty() {
        return bookingCount == 0 && handoffCount == 0 && questionCount == 0;
    }
}
