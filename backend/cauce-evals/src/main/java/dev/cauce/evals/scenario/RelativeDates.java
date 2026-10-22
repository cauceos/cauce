package dev.cauce.evals.scenario;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;

/**
 * The relative dates of the scenario format (ADR 0009, E3): {@code +N} (or {@code N}) is N days
 * after today, {@code proximo_<dia>} the next such weekday strictly after today. "Today" is the
 * business's local date on the database clock (ADR 0008), passed in by the runner.
 */
public final class RelativeDates {

    private static final Map<String, DayOfWeek> DAYS = Map.of(
            "lunes", DayOfWeek.MONDAY, "martes", DayOfWeek.TUESDAY, "miercoles", DayOfWeek.WEDNESDAY,
            "jueves", DayOfWeek.THURSDAY, "viernes", DayOfWeek.FRIDAY, "sabado", DayOfWeek.SATURDAY,
            "domingo", DayOfWeek.SUNDAY);

    private RelativeDates() {
    }

    public static LocalDate resolve(String spec, LocalDate today) {
        String value = spec.strip();
        if (value.startsWith("proximo_")) {
            DayOfWeek day = DAYS.get(value.substring("proximo_".length()));
            if (day == null) {
                throw new InvalidScenarioException("not a weekday: " + spec);
            }
            return today.with(TemporalAdjusters.next(day));
        }
        return today.plusDays(Long.parseLong(value.startsWith("+") ? value.substring(1) : value));
    }
}
