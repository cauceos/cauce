package dev.cauce.api.business;

import dev.cauce.core.business.booking.Booking;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * A booking over the wire, for the owner. A HELD booking whose {@code holdExpiresAt} has passed
 * holds nothing: it was never confirmed. {@code time} is the time the person asked for within the
 * shift, null when they named only the shift.
 */
public record BookingResponse(
        UUID id,
        UUID tenantId,
        String code,
        UUID agentId,
        UUID conversationId,
        UUID identityId,
        LocalDate date,
        String shift,
        LocalTime time,
        int partySize,
        String contactName,
        String contactPhone,
        String notes,
        String status,
        UUID replacesBookingId,
        Instant holdExpiresAt,
        Instant cancellationExpiresAt,
        Instant createdAt,
        Instant updatedAt) {

    public static BookingResponse from(Booking booking) {
        return new BookingResponse(booking.id(), booking.tenantId(), booking.code(), booking.agentId(),
                booking.conversationId(), booking.identityId(), booking.date(), booking.shiftId(),
                booking.time().orElse(null), booking.partySize(),
                booking.contactName(), booking.contactPhone().orElse(null), booking.notes().orElse(null),
                booking.status().name(), booking.replacesBookingId().orElse(null), booking.holdExpiresAt(),
                booking.cancellationExpiresAt().orElse(null), booking.createdAt(), booking.updatedAt());
    }
}
