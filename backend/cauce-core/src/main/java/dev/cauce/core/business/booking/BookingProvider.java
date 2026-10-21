package dev.cauce.core.business.booking;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port: the bookings of a business (ADR 0007, B2, B6). The native adapter is cauce-business; a
 * calendar or an ERP would be another adapter behind the same operations, and the agent, its tools
 * and its scenarios would not change.
 *
 * <p>The person a booking belongs to is the {@link Booking.Requester}'s identity, taken from the
 * conversation: an operation on a code that belongs to someone else is
 * {@link BookingRefusal.Reason#NOT_FOUND}. Every write is a proposal confirmed in a later turn
 * ({@link #confirm}). A refused action throws {@link BookingRefusedException}; a business with no
 * agenda throws {@link AgendaNotFoundException}.
 */
public interface BookingProvider {

    /** The agenda of the business, if one is loaded and visible. */
    Optional<Agenda> findAgenda(UUID tenantId);

    /** What remains to book on {@code date}. */
    DayAvailability availability(UUID tenantId, LocalDate date);

    /** Proposes a new booking, holding capacity until the person confirms it. */
    Booking propose(Booking.Requester requester, Booking.Details details);

    /**
     * Proposes a change of the requester's confirmed booking {@code code}: a new held booking with
     * the changed fields that, once confirmed, replaces it.
     */
    Booking proposeChange(Booking.Requester requester, String code, Change change);

    /** Requests the cancellation of the requester's confirmed booking {@code code}. */
    Booking proposeCancellation(Booking.Requester requester, String code);

    /**
     * Confirms what booking {@code code} of the requester is waiting for: a proposal (and the
     * cancellation of the booking it replaces) or a cancellation. Refused from the turn it was
     * proposed in.
     */
    Booking confirm(Booking.Requester requester, String code);

    /** The requester identity's bookings from today on: confirmed, and proposals still pending. */
    List<Booking> upcomingFor(UUID tenantId, UUID identityId);

    /** The confirmed bookings on {@code date}, by shift start: what the owner prepares for. */
    List<Booking> confirmedOn(UUID tenantId, LocalDate date);

    /** The fields of a change; a null field keeps the booking's current value. */
    record Change(LocalDate date, String shiftId, Integer partySize, String notes) {
    }
}
