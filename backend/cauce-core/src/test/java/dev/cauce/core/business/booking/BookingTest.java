package dev.cauce.core.business.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.business.booking.Booking.Details;
import dev.cauce.core.business.booking.Booking.Pending;
import dev.cauce.core.business.booking.Booking.Requester;
import dev.cauce.core.business.booking.BookingRefusal.Reason;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BookingTest {

    private final UUID proposalMessage = UUID.randomUUID();
    private final Requester requester = new Requester(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), proposalMessage);
    private final Details details = new Details(LocalDate.of(2026, 10, 24), "cena", null, 4, " Ana López ", " ", null);
    private final Instant now = Instant.now();

    @Test
    void propose_holdsUntilTheExpiry_withAReadableCode() {
        Booking booking = Booking.propose(requester, details, now.plus(Duration.ofMinutes(10)));

        assertThat(booking.status()).isEqualTo(Booking.Status.HELD);
        assertThat(booking.contactName()).isEqualTo("Ana López");
        assertThat(booking.contactPhone()).isEmpty();
        assertThat(booking.code()).hasSize(Booking.CODE_LENGTH).matches("[A-HJ-NP-Z2-9]+");
        assertThat(booking.pendingAt(now)).contains(Pending.CONFIRMATION);
        assertThat(booking.takesCapacityAt(now)).isTrue();
        assertThat(booking.takesCapacityAt(now.plus(Duration.ofMinutes(11)))).isFalse();
        assertThat(booking.identityId()).isEqualTo(requester.identityId());
    }

    @Test
    void confirmationRefusal_refusesTheSameTurn_anExpiredHold_andNothingPending() {
        Booking held = Booking.propose(requester, details, now.plus(Duration.ofMinutes(10)));

        assertThat(held.confirmationRefusal(proposalMessage, now).map(BookingRefusal::reason)).contains(Reason.SAME_TURN);
        assertThat(held.confirmationRefusal(UUID.randomUUID(), now)).isEmpty();
        assertThat(held.confirmationRefusal(UUID.randomUUID(), now.plus(Duration.ofMinutes(11)))
                .map(BookingRefusal::reason)).contains(Reason.EXPIRED);
        assertThat(held.confirmed().confirmationRefusal(UUID.randomUUID(), now).map(BookingRefusal::reason))
                .contains(Reason.NOT_PENDING);
    }

    @Test
    void aCancellationRequest_isPendingOnTheConfirmedBooking_withItsOwnTurn() {
        Booking confirmed = Booking.propose(requester, details, now.plus(Duration.ofMinutes(10))).confirmed();
        UUID cancelMessage = UUID.randomUUID();

        Booking requested = confirmed.withCancellationRequested(cancelMessage, now.plus(Duration.ofMinutes(10)));

        assertThat(requested.pendingAt(now)).contains(Pending.CANCELLATION);
        assertThat(requested.confirmationRefusal(cancelMessage, now).map(BookingRefusal::reason))
                .contains(Reason.SAME_TURN);
        assertThat(requested.confirmationRefusal(UUID.randomUUID(), now)).isEmpty();
        assertThat(requested.cancelled().status()).isEqualTo(Booking.Status.CANCELLED);
        assertThatThrownBy(() -> requested.cancelled().withCancellationRequested(cancelMessage, now))
                .isInstanceOf(IllegalStateException.class);
        assertThat(requested.withoutCancellationRequest().pendingAt(now)).isEmpty();
        assertThat(confirmed.withoutCancellationRequest()).isSameAs(confirmed);
    }

    @Test
    void proposeChange_keepsTheCodeOfTheConfirmedBookingItReplaces() {
        Booking confirmed = Booking.propose(requester, details, now.plus(Duration.ofMinutes(10))).confirmed();

        Booking change = Booking.proposeChange(confirmed, requester, details, now.plus(Duration.ofMinutes(10)));

        assertThat(change.code()).isEqualTo(confirmed.code());
        assertThat(change.id()).isNotEqualTo(confirmed.id());
        assertThat(change.replacesBookingId()).contains(confirmed.id());
        assertThat(change.status()).isEqualTo(Booking.Status.HELD);
        assertThatThrownBy(() -> Booking.proposeChange(change, requester, details, now))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void propose_keepsTheRequestedTimeToTheMinute_andNoTimeWhenOnlyTheShiftWasNamed() {
        Details at = new Details(LocalDate.of(2026, 10, 24), "cena", LocalTime.of(21, 30, 15), 4, "Ana", null, null);

        assertThat(at.time()).isEqualTo(LocalTime.of(21, 30));
        assertThat(Booking.propose(requester, at, now.plus(Duration.ofMinutes(10))).time())
                .contains(LocalTime.of(21, 30));
        assertThat(Booking.propose(requester, details, now.plus(Duration.ofMinutes(10))).time()).isEmpty();
    }

    @Test
    void details_requireAShiftAndAName_andBoundTheirLength() {
        assertThatThrownBy(() -> new Details(LocalDate.now(), " ", null, 2, "Ana", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Details(LocalDate.now(), "cena", null, 2, " ", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Details(LocalDate.now(), "cena", null, 2, "Ana", null,
                "x".repeat(Booking.MAX_NOTES_LENGTH + 1))).isInstanceOf(IllegalArgumentException.class);
    }
}
