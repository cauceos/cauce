package dev.cauce.evals.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RelativeDatesTest {

    private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 22);

    @Test
    void resolve_daysAheadAndTheNextWeekdayStrictlyAfterToday() {
        assertThat(RelativeDates.resolve("+1", THURSDAY)).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(RelativeDates.resolve("3", THURSDAY)).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(RelativeDates.resolve("proximo_sabado", THURSDAY)).isEqualTo(LocalDate.of(2026, 10, 24));
        assertThat(RelativeDates.resolve("proximo_jueves", THURSDAY)).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThatThrownBy(() -> RelativeDates.resolve("proximo_finde", THURSDAY))
                .isInstanceOf(InvalidScenarioException.class);
        assertThat(RelativeDates.resolve("proximo_sabado+21", THURSDAY)).isEqualTo(LocalDate.of(2026, 11, 14));
    }

    @Test
    void render_writesOutTheDatesOfWhatThePersonSays_soNoScenarioDependsOnTheDayItRuns() {
        LocalDate friday = LocalDate.of(2026, 10, 23);

        assertThat(RelativeDates.render("Mesa para 4 el {proximo_sabado} a las 21:30",
                spec -> RelativeDates.resolve(spec, friday))).isEqualTo("Mesa para 4 el sábado 24 de octubre a las 21:30");
        // H3.8 ran on a Friday and «el viernes» was ambiguous; this is the ambiguity, on purpose.
        assertThat(RelativeDates.render("Mesa el {+0:dia_semana}", spec -> RelativeDates.resolve(spec, friday)))
                .isEqualTo("Mesa el viernes");
        assertThat(RelativeDates.render("Sin fechas", spec -> friday)).isEqualTo("Sin fechas");
        assertThatThrownBy(() -> RelativeDates.validatePlaceholders("el {mañana}"))
                .isInstanceOf(InvalidScenarioException.class);
        assertThatThrownBy(() -> RelativeDates.validatePlaceholders("el {+1:mes}"))
                .isInstanceOf(InvalidScenarioException.class);
    }
}
