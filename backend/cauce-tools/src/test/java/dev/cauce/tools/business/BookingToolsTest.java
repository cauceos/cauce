package dev.cauce.tools.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.cauce.core.business.booking.Agenda;
import dev.cauce.core.business.booking.Agenda.Settings;
import dev.cauce.core.business.booking.Agenda.Shift;
import dev.cauce.core.business.booking.Booking;
import dev.cauce.core.business.booking.BookingProvider;
import dev.cauce.core.business.booking.BookingRefusal;
import dev.cauce.core.business.booking.BookingRefusal.Reason;
import dev.cauce.core.business.booking.DayAvailability;
import dev.cauce.core.business.booking.DayAvailability.ShiftAvailability;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolExecutionContext;
import dev.cauce.tools.spi.Tool;
import dev.cauce.tools.spi.ToolArguments.InvalidToolArgumentException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BookingToolsTest {

    private static final Instant NOW = Instant.parse("2026-11-01T18:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Shift CENA = new Shift("cena", EnumSet.allOf(DayOfWeek.class), LocalTime.of(20, 30),
            LocalTime.of(23, 0), 50);

    private final ToolExecutionContext context = new ToolExecutionContext(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private final BookingProvider provider = mock(BookingProvider.class);
    private final LocalDate date = LocalDate.of(2026, 11, 3);

    @BeforeEach
    void setUp() {
        when(provider.findAgenda(context.tenantId())).thenReturn(Optional.of(Agenda.create(context.tenantId(),
                new Settings("comensales", 8, 10, 60, false, List.of(CENA), Set.of()))));
    }

    @Test
    void proposeBooking_actsForTheContextsPerson_andTellsTheModelToAskFirst() {
        ArgumentCaptor<Booking.Requester> requester = ArgumentCaptor.forClass(Booking.Requester.class);
        when(provider.propose(requester.capture(), any())).thenAnswer(call -> Booking.propose(call.getArgument(0),
                call.getArgument(1), NOW.plusSeconds(600)));

        String output = run(BookingTools.proposeBooking(() -> provider, CLOCK), Map.of("date", "2026-11-03", "shift", "cena",
                "party_size", "4", "contact_name", "Ana", "identity_id", UUID.randomUUID().toString()));

        assertThat(requester.getValue()).isEqualTo(new Booking.Requester(context.tenantId(), context.agentId(),
                context.conversationId(), context.identityId(), context.triggerMessageId()));
        assertThat(output).contains("\"ok\":true").contains("\"status\":\"HELD\"").contains("\"start\":\"20:30\"")
                .contains("\"waiting_for\":\"confirmation\"").contains("\"confirm_within_minutes\":10")
                .contains("never in this same turn");
    }

    @Test
    void proposeBooking_passesTheRequestedTime_andReadsItBack() {
        when(provider.propose(any(), any())).thenAnswer(call -> Booking.propose(call.getArgument(0),
                call.getArgument(1), NOW.plusSeconds(600)));

        String output = run(BookingTools.proposeBooking(() -> provider, CLOCK), Map.of("date", "2026-11-03",
                "shift", "cena", "time", "21:30", "party_size", 4, "contact_name", "Ana"));

        assertThat(output).contains("\"time\":\"21:30\"").contains("\"start\":\"20:30\"");
        assertThatThrownBy(() -> run(BookingTools.proposeBooking(() -> provider, CLOCK), Map.of("date", "2026-11-03",
                "shift", "cena", "time", "half past nine", "party_size", 4, "contact_name", "Ana")))
                .isInstanceOf(InvalidToolArgumentException.class).hasMessageContaining("HH:MM");
    }

    @Test
    void aProposedChange_keepsTheCode_andIsMarkedAsAPendingChange() {
        Booking.Requester requester = new Booking.Requester(context.tenantId(), context.agentId(),
                context.conversationId(), context.identityId(), context.triggerMessageId());
        Booking confirmed = Booking.propose(requester, new Booking.Details(date, "cena", null, 2, "Ana", null, null),
                NOW.plusSeconds(600)).confirmed();
        when(provider.proposeChange(any(), eq(confirmed.code()), any())).thenAnswer(call -> Booking.proposeChange(
                confirmed, call.getArgument(0), new Booking.Details(date, "cena", null, 4, "Ana", null, null),
                NOW.plusSeconds(600)));

        String output = run(BookingTools.proposeBookingChange(() -> provider, CLOCK), Map.of("code", confirmed.code(),
                "party_size", 4));

        assertThat(output).contains("\"code\":\"" + confirmed.code() + "\"").contains("\"pending_change\":true");
    }

    @Test
    void aHeldBooking_waitsForConfirmationOnlyUntilItsHoldEnds_onTheInjectedClock() {
        Booking held = Booking.propose(new Booking.Requester(context.tenantId(), context.agentId(),
                context.conversationId(), context.identityId(), context.triggerMessageId()),
                new Booking.Details(date, "cena", null, 2, "Ana", null, null), NOW.plusSeconds(600));
        when(provider.upcomingFor(context.tenantId(), context.identityId())).thenReturn(List.of(held));

        // The clock is the database's (ADR 0008), the one the hold was stamped with: not the JVM's.
        String open = run(BookingTools.listMyBookings(() -> provider, CLOCK), Map.of());
        String lapsed = run(BookingTools.listMyBookings(() -> provider,
                Clock.fixed(NOW.plusSeconds(601), ZoneOffset.UTC)), Map.of());

        assertThat(open).contains("\"waiting_for\":\"confirmation\"");
        assertThat(lapsed).doesNotContain("waiting_for");
    }

    @Test
    void aRefusal_isAnAnswerWithItsReason_notAnError() {
        when(provider.confirm(any(), eq("ABC234"))).thenThrow(new BookingRefusal(Reason.SAME_TURN, "ask first")
                .toException());

        String output = run(BookingTools.confirmBooking(() -> provider, CLOCK), Map.of("code", "ABC234"));

        assertThat(output).isEqualTo("{\"message\":\"ask first\",\"ok\":false,\"reason\":\"SAME_TURN\"}");
    }

    @Test
    void checkAvailability_reportsTheShiftsOfTheDay() {
        when(provider.availability(context.tenantId(), date)).thenReturn(new DayAvailability(date, false,
                List.of(new ShiftAvailability("cena", CENA.start(), CENA.end(), 50, 12, true))));

        String output = run(BookingTools.checkAvailability(() -> provider), Map.of("date", "2026-11-03"));

        assertThat(output).contains("\"weekday\":\"tuesday\"").contains("\"remaining\":12")
                .contains("\"max_party_size\":8").contains("\"closed\":false");
    }

    @Test
    void aMalformedDate_andABlankName_areArgumentsTheModelMustFix() {
        assertThatThrownBy(() -> run(BookingTools.checkAvailability(() -> provider), Map.of("date", "martes")))
                .isInstanceOf(InvalidToolArgumentException.class).hasMessageContaining("YYYY-MM-DD");
        assertThatThrownBy(() -> run(BookingTools.proposeBooking(() -> provider, CLOCK), Map.of("date", "2026-11-03",
                "shift", "cena", "party_size", 2, "contact_name", " ")))
                .isInstanceOf(InvalidToolArgumentException.class);
    }

    @Test
    void withoutAnAgendaOrAProvider_bookingsAreUnavailable() {
        BookingProvider empty = mock(BookingProvider.class);
        when(empty.findAgenda(any())).thenReturn(Optional.empty());

        assertThat(run(BookingTools.checkAvailability(() -> empty), Map.of("date", "2026-11-03")))
                .contains("\"available\":false");
        assertThat(run(BookingTools.listMyBookings(() -> null, CLOCK), Map.of())).contains("\"available\":false");
    }

    private String run(Tool tool, Map<String, Object> input) {
        return tool.execute(new ToolCall("call-1", tool.definition().name(), input), context).output();
    }
}
