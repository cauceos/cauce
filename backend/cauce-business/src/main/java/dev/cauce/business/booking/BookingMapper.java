package dev.cauce.business.booking;

import dev.cauce.core.business.booking.Booking;
import org.springframework.stereotype.Component;

/** Hand-written mapper between {@link Booking} and {@link BookingEntity}. */
@Component
public class BookingMapper {

    public BookingEntity toEntity(Booking booking) {
        return new BookingEntity(booking.id(), booking.tenantId(), booking.code(), booking.agentId(),
                booking.conversationId(), booking.identityId(), booking.date(), booking.shiftId(),
                booking.time().orElse(null), booking.partySize(),
                booking.contactName(), booking.contactPhone().orElse(null), booking.notes().orElse(null),
                booking.status(), booking.replacesBookingId().orElse(null), booking.proposedInMessageId(),
                booking.holdExpiresAt(), booking.cancellationRequestedInMessageId().orElse(null),
                booking.cancellationExpiresAt().orElse(null), booking.createdAt(), booking.updatedAt());
    }

    public Booking toDomain(BookingEntity entity) {
        return Booking.rehydrate(entity.getId(), entity.getTenantId(), entity.getCode(), entity.getAgentId(),
                entity.getConversationId(), entity.getIdentityId(), entity.getServiceDate(), entity.getShiftId(),
                entity.getRequestedTime(), entity.getPartySize(), entity.getContactName(), entity.getContactPhone(), entity.getNotes(),
                entity.getStatus(), entity.getReplacesBookingId(), entity.getProposedInMessageId(),
                entity.getHoldExpiresAt(), entity.getCancellationRequestedInMessageId(),
                entity.getCancellationExpiresAt(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
