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
    }
}
