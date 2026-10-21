package dev.cauce.core.business.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.business.booking.Agenda.Settings;
import dev.cauce.core.business.booking.Agenda.Shift;
import dev.cauce.core.business.booking.BookingRefusal.Reason;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgendaTest {

    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    private static final Shift CENA = new Shift("cena", Set.of(DayOfWeek.TUESDAY, DayOfWeek.SATURDAY),
            LocalTime.of(20, 30), LocalTime.of(23, 0), 50);
    // Tuesday 2026-10-20, 12:00 in Madrid.
    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");

    private final Agenda agenda = Agenda.create(UUID.randomUUID(),
            new Settings("comensales", 8, 10, 60, true, List.of(CENA), Set.of(LocalDate.of(2026, 10, 24))));

    @Test
    void shiftsOn_followTheWeekdays_andNoneOnAClosedDate() {
        assertThat(agenda.shiftsOn(LocalDate.of(2026, 10, 20))).containsExactly(CENA);
        assertThat(agenda.shiftsOn(LocalDate.of(2026, 10, 19))).isEmpty(); // Monday
        assertThat(agenda.shiftsOn(LocalDate.of(2026, 10, 24))).isEmpty(); // a closed Saturday
        assertThat(agenda.shift("CENA ")).contains(CENA);
    }

    @Test
    void refusalFor_coversSizeDayStartAndHorizon() {
        assertThat(agenda.refusalFor(LocalDate.of(2026, 10, 20), CENA, 4, NOW, MADRID)).isEmpty();
        assertThat(reason(LocalDate.of(2026, 10, 20), 12, NOW)).contains(Reason.PARTY_TOO_LARGE);
        assertThat(reason(LocalDate.of(2026, 10, 21), 2, NOW)).contains(Reason.CLOSED);
        assertThat(reason(LocalDate.of(2026, 10, 24), 2, NOW)).contains(Reason.CLOSED);
        assertThat(reason(LocalDate.of(2026, 10, 20), 2, Instant.parse("2026-10-20T18:30:00Z"))).contains(Reason.PAST);
        assertThat(reason(LocalDate.of(2027, 1, 5), 2, NOW)).contains(Reason.TOO_FAR);
        assertThat(reason(LocalDate.of(2026, 10, 20), 0, NOW)).contains(Reason.INVALID);
    }

    @Test
    void settings_rejectInvalidShiftsAndBounds() {
        assertThatThrownBy(() -> new Shift("x", Set.of(DayOfWeek.MONDAY), LocalTime.NOON, LocalTime.NOON, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings("u", 8, 10, 60, false, List.of(CENA, CENA), Set.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("twice");
        assertThatThrownBy(() -> new Settings("u", 0, 10, 60, false, List.of(CENA), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings("u", 8, 10, 60, false, List.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void replacedBy_keepsIdentity_andSettingsRoundTrip() {
        Agenda replaced = agenda.replacedBy(new Settings("sillas", 2, 5, 7, false, List.of(CENA), Set.of()));

        assertThat(replaced).isEqualTo(agenda);
        assertThat(replaced.unit()).isEqualTo("sillas");
        assertThat(replaced.settings().closedDates()).isEmpty();
        assertThat(agenda.settings().closedDates()).containsExactly(LocalDate.of(2026, 10, 24));
    }

    private Optional<Reason> reason(LocalDate date, int party, Instant now) {
        return agenda.refusalFor(date, CENA, party, now, MADRID).map(BookingRefusal::reason);
    }
}
