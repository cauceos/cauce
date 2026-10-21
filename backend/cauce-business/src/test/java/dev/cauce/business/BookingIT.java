package dev.cauce.business;

import static dev.cauce.business.support.BusinessFixture.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.business.booking.AgendaService;
import dev.cauce.business.booking.BookingService;
import dev.cauce.business.profile.BusinessProfileService;
import dev.cauce.business.support.AbstractBusinessIntegrationTest;
import dev.cauce.business.support.BusinessFixture;
import dev.cauce.core.business.booking.Agenda.Settings;
import dev.cauce.core.business.booking.Agenda.Shift;
import dev.cauce.core.business.booking.Booking;
import dev.cauce.core.business.booking.Booking.Details;
import dev.cauce.core.business.booking.Booking.Requester;
import dev.cauce.core.business.booking.BookingProvider.Change;
import dev.cauce.core.business.booking.BookingRefusal.Reason;
import dev.cauce.core.business.booking.BookingRefusedException;
import dev.cauce.core.business.booking.DayAvailability;
import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.identity.IdentityKind;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.tenancy.AgentService;
import dev.cauce.tenancy.ConversationService;
import dev.cauce.tenancy.TenantService;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The guarantees of the native bookings that the code enforces, whatever the model does (ADR 0007,
 * B3, B6), against a real PostgreSQL under RLS: confirmation only from a later turn, a person acts
 * only on their own bookings, capacity is never exceeded — not even by concurrent proposals — and
 * a change replaces the booking it changes.
 */
class BookingIT extends AbstractBusinessIntegrationTest {

    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");

    @Autowired
    private TenantService tenantService;

    @Autowired
    private AgentService agentService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private BusinessProfileService profileService;

    @Autowired
    private AgendaService agendaService;

    @Autowired
    private BookingService bookings;

    private BusinessFixture f;
    private Conversation other;
    private LocalDate nextTuesday;

    @BeforeEach
    void setUp() {
        truncateAll();
        f = BusinessFixture.seed(tenantService, agentService, conversationService);
        other = as(f.clientA(), () -> conversationService.startConversation(f.agent().id(), "api",
                IdentityKind.CLIENT_REFERENCE, "cliente-2", null));
        as(f.clientA(), () -> profileService.putProfile(f.clientA().id(), "Bar", "Europe/Madrid", null, List.of()));
        as(f.clientA(), () -> agendaService.putAgenda(f.clientA().id(), new Settings("comensales", 8, 10, 60, false,
                List.of(new Shift("cena", EnumSet.allOf(DayOfWeek.class), LocalTime.of(20, 30), LocalTime.of(23, 0),
                        10)), Set.of())));
        nextTuesday = LocalDate.now(clock.withZone(MADRID)).plusDays(1);
        while (nextTuesday.getDayOfWeek() != DayOfWeek.TUESDAY) {
            nextTuesday = nextTuesday.plusDays(1);
        }
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void aProposal_isConfirmedOnlyFromALaterTurn() {
        Requester turn1 = requester(f.conversation(), UUID.randomUUID());
        Booking held = asA(() -> bookings.propose(turn1, details(4)));

        assertThat(held.status()).isEqualTo(Booking.Status.HELD);
        assertRefused(() -> bookings.confirm(turn1, held.code()), Reason.SAME_TURN);

        Booking confirmed = asA(() -> bookings.confirm(requester(f.conversation(), UUID.randomUUID()),
                held.code().toLowerCase()));
        assertThat(confirmed.status()).isEqualTo(Booking.Status.CONFIRMED);
        assertThat(asA(() -> bookings.confirmedOn(f.clientA().id(), nextTuesday))).containsExactly(confirmed);
    }

    @Test
    void aPersonCannotSeeConfirmChangeOrCancelSomeoneElsesBooking() {
        Booking held = asA(() -> bookings.propose(requester(f.conversation(), UUID.randomUUID()), details(2)));
        Requester stranger = requester(other, UUID.randomUUID());

        assertRefused(() -> bookings.confirm(stranger, held.code()), Reason.NOT_FOUND);
        assertRefused(() -> bookings.proposeCancellation(stranger, held.code()), Reason.NOT_FOUND);
        assertRefused(() -> bookings.proposeChange(stranger, held.code(), new Change(null, null, 3, null)),
                Reason.NOT_FOUND);
        assertThat(asA(() -> bookings.upcomingFor(f.clientA().id(), other.identityId()))).isEmpty();
        assertThat(asA(() -> bookings.upcomingFor(f.clientA().id(), f.conversation().identityId())))
                .extracting(Booking::code).containsExactly(held.code());
    }

    @Test
    void capacity_countsConfirmedBookingsAndLiveHolds_andRefusesBeyondIt() {
        confirm(f.conversation(), details(6));
        Booking held = asA(() -> bookings.propose(requester(other, UUID.randomUUID()), details(3)));

        DayAvailability day = asA(() -> bookings.availability(f.clientA().id(), nextTuesday));
        assertThat(day.shifts()).singleElement().satisfies(shift -> {
            assertThat(shift.remaining()).isEqualTo(1);
            assertThat(shift.bookable()).isTrue();
        });
        assertRefused(() -> bookings.propose(requester(other, UUID.randomUUID()), details(2)), Reason.FULL);
        assertRefused(() -> bookings.propose(requester(other, UUID.randomUUID()), details(9)),
                Reason.PARTY_TOO_LARGE);
        assertThat(held.takesCapacityAt(clock.instant())).isTrue();
    }

    @Test
    void concurrentProposalsForTheLastPlaces_neverOverbook() throws Exception {
        confirm(f.conversation(), details(6)); // 4 places left
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                Callable<Boolean> attempt = () -> {
                    start.await();
                    try {
                        asA(() -> bookings.propose(requester(other, UUID.randomUUID()), details(3)));
                        return true;
                    } catch (BookingRefusedException e) {
                        return false;
                    }
                };
                results.add(pool.submit(attempt));
            }
            start.countDown();
            long accepted = 0;
            for (Future<Boolean> result : results) {
                accepted += result.get() ? 1 : 0;
            }
            assertThat(accepted).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(asA(() -> bookings.availability(f.clientA().id(), nextTuesday)).shifts().get(0).remaining())
                .isEqualTo(1);
    }

    @Test
    void aConfirmedChange_replacesTheBooking_andItsOwnPlacesDoNotCountAgainstIt() {
        Booking original = confirm(f.conversation(), details(8)); // 2 places left
        Requester turn = requester(f.conversation(), UUID.randomUUID());

        // 8 -> 10 fits because the original's 8 places are its own.
        assertRefused(() -> bookings.proposeChange(turn, original.code(), new Change(null, null, 9, null)),
                Reason.PARTY_TOO_LARGE);
        Booking change = asA(() -> bookings.proposeChange(turn, original.code(), new Change(null, null, 7, "Trona")));
        Booking confirmed = asA(() -> bookings.confirm(requester(f.conversation(), UUID.randomUUID()), change.code()));

        assertThat(confirmed.partySize()).isEqualTo(7);
        assertThat(confirmed.notes()).contains("Trona");
        assertThat(confirmed.replacesBookingId()).contains(original.id());
        assertThat(asA(() -> bookings.confirmedOn(f.clientA().id(), nextTuesday)))
                .extracting(Booking::code).containsExactly(change.code());
    }

    @Test
    void aCancellation_isRequestedThenConfirmedInALaterTurn() {
        Booking booking = confirm(f.conversation(), details(2));
        Requester ask = requester(f.conversation(), UUID.randomUUID());

        asA(() -> bookings.proposeCancellation(ask, booking.code()));
        assertRefused(() -> bookings.confirm(ask, booking.code()), Reason.SAME_TURN);
        Booking cancelled = asA(() -> bookings.confirm(requester(f.conversation(), UUID.randomUUID()), booking.code()));

        assertThat(cancelled.status()).isEqualTo(Booking.Status.CANCELLED);
        assertThat(asA(() -> bookings.availability(f.clientA().id(), nextTuesday)).shifts().get(0).remaining())
                .isEqualTo(10);
    }

    @Test
    void bookingsAreVisibleUpTheHierarchyOnly() {
        confirm(f.conversation(), details(2));

        assertThat(countAs("bookings", f.partner().id())).isEqualTo(1);
        assertThat(countAs("bookings", f.clientB().id())).isZero();
        assertThat(as(f.clientB(), () -> bookings.listForTenant(f.clientA().id(), null, null, 10))).isEmpty();
        assertThat(as(f.partner(), () -> bookings.listForTenant(f.clientA().id(), nextTuesday, null, 10))).hasSize(1);
    }

    private Booking confirm(Conversation conversation, Details details) {
        Booking held = asA(() -> bookings.propose(requester(conversation, UUID.randomUUID()), details));
        return asA(() -> bookings.confirm(requester(conversation, UUID.randomUUID()), held.code()));
    }

    private Details details(int party) {
        return new Details(nextTuesday, "Cena", party, "Ana", null, null);
    }

    private Requester requester(Conversation conversation, UUID messageId) {
        return new Requester(f.clientA().id(), f.agent().id(), conversation.id(), conversation.identityId(), messageId);
    }

    private <T> T asA(Supplier<T> action) {
        return as(f.clientA(), action);
    }

    private void assertRefused(Supplier<Booking> action, Reason reason) {
        assertThatThrownBy(() -> asA(action))
                .isInstanceOfSatisfying(BookingRefusedException.class,
                        e -> assertThat(e.refusal().reason()).isEqualTo(reason));
    }
}
