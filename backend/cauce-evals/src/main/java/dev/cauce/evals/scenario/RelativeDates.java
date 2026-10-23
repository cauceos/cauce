package dev.cauce.evals.scenario;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The relative dates of the scenario format (ADR 0009, E3): {@code +N} (or {@code N}) is N days
 * after today, {@code proximo_<dia>} the next such weekday strictly after today, and
 * {@code proximo_<dia>+N} N days after that. "Today" is the business's local date on the database
 * clock (ADR 0008), passed in by the runner.
 *
 * <p>What a person writes uses them too, so no scenario depends on the day it runs: in a
 * {@code cliente} text, {@code {proximo_sabado}} becomes «sábado 24 de octubre» and
 * {@code {+0:dia_semana}} today's weekday alone, «viernes».
 */
public final class RelativeDates {

    /** A date the format accepts. */
    public static final Pattern SPEC = Pattern.compile(
            "\\+?\\d+|proximo_(lunes|martes|miercoles|jueves|viernes|sabado|domingo)(\\+\\d+)?");

    /** A date in a {@code cliente} text: {@code {spec}} or {@code {spec:dia_semana}}. */
    public static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}:]+)(?::([a-z_]+))?}");

    private static final String WEEKDAY = "dia_semana";
    private static final Locale SPANISH = Locale.forLanguageTag("es-ES");
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", SPANISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE", SPANISH);

    private static final Map<String, DayOfWeek> DAYS = Map.of(
            "lunes", DayOfWeek.MONDAY, "martes", DayOfWeek.TUESDAY, "miercoles", DayOfWeek.WEDNESDAY,
            "jueves", DayOfWeek.THURSDAY, "viernes", DayOfWeek.FRIDAY, "sabado", DayOfWeek.SATURDAY,
            "domingo", DayOfWeek.SUNDAY);

    private RelativeDates() {
    }

    public static LocalDate resolve(String spec, LocalDate today) {
        String value = spec.strip();
        if (value.startsWith("proximo_")) {
            String[] parts = value.substring("proximo_".length()).split("\\+", 2);
            DayOfWeek day = DAYS.get(parts[0]);
            if (day == null) {
                throw new InvalidScenarioException("not a weekday: " + spec);
            }
            LocalDate next = today.with(TemporalAdjusters.next(day));
            return parts.length == 1 ? next : next.plusDays(Long.parseLong(parts[1]));
        }
        return today.plusDays(Long.parseLong(value.startsWith("+") ? value.substring(1) : value));
    }

    /** {@code text} with each date placeholder written out, in Spanish, through {@code resolve}. */
    public static String render(String text, Function<String, LocalDate> resolve) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            requireValid(m.group(1), m.group(2));
            LocalDate date = resolve.apply(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement((m.group(2) == null ? FULL : DAY).format(date)));
        }
        return m.appendTail(out).toString();
    }

    /** Throws when a placeholder of {@code text} is not a date the format accepts. */
    public static void validatePlaceholders(String text) {
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            requireValid(m.group(1), m.group(2));
        }
    }

    private static void requireValid(String spec, String format) {
        if (!SPEC.matcher(spec).matches() || format != null && !format.equals(WEEKDAY)) {
            throw new InvalidScenarioException("not a date placeholder: {" + spec + (format == null ? "" : ":" + format)
                    + "} (use {+N}, {proximo_<dia>}, {proximo_<dia>+N}, optionally with :" + WEEKDAY + ")");
        }
    }
}
