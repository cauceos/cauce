package dev.cauce.evals.check;

import dev.cauce.evals.check.TurnTrace.ObservedBooking;
import dev.cauce.evals.check.TurnTrace.ObservedCase;
import dev.cauce.evals.check.TurnTrace.ObservedNotice;
import dev.cauce.evals.check.TurnTrace.ObservedState;
import dev.cauce.evals.check.TurnTrace.ToolUse;
import dev.cauce.evals.scenario.RelativeDates;
import dev.cauce.evals.scenario.Scenario.Expectation;
import dev.cauce.evals.scenario.Scenario.ExpectedState;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The deterministic checks of one expectation against what a turn left behind (ADR 0009, E4, E5).
 * The rubric is not checked here: it goes to the judge. Variables ({@code $r.codigo}) are replaced
 * by what the scenario's {@code previo} minted, and relative dates by the business's calendar.
 */
public final class Checker {

    private static final Pattern VARIABLE = Pattern.compile("\\$([a-z][a-z0-9_]*)\\.codigo");

    /** An argument that is a relative date in braces, {@code "{+1}"}: the tools take it as YYYY-MM-DD. */
    private static final Pattern DATE_ARGUMENT = Pattern.compile("\\{(" + RelativeDates.SPEC.pattern() + ")\\}");

    private final Map<String, String> codes;
    private final Function<String, LocalDate> dates;

    /**
     * @param codes the booking code each {@code previo} alias minted
     * @param dates resolves {@code +N} and {@code proximo_<dia>} in the business's time zone
     */
    public Checker(Map<String, String> codes, Function<String, LocalDate> dates) {
        this.codes = Map.copyOf(codes);
        this.dates = dates;
    }

    /** The checks of {@code e} on {@code turn}, prefixed with {@code prefix} ({@code turno 2}). */
    public List<CheckResult> check(String prefix, Expectation e, TurnTrace turn) {
        List<CheckResult> results = new ArrayList<>();
        List<String> called = turn.tools().stream().map(ToolUse::name).toList();
        if (e.tools() != null) {
            List<String> missing = e.tools().stream().filter(t -> !called.contains(t)).toList();
            results.add(CheckResult.of(prefix + " · herramientas", missing.isEmpty(),
                    missing.isEmpty() ? "llamadas: " + called : "faltan " + missing + "; llamadas: " + called));
        }
        if (e.order() != null) {
            boolean inOrder = isSubsequence(e.order(), called);
            results.add(CheckResult.of(prefix + " · orden", inOrder, "esperado " + e.order() + "; llamadas: " + called));
        }
        if (e.forbiddenTools() != null) {
            List<String> used = e.forbiddenTools().stream().filter(called::contains).toList();
            results.add(CheckResult.of(prefix + " · herramientas_no", used.isEmpty(),
                    used.isEmpty() ? "ninguna prohibida" : "llamó a " + used));
        }
        if (e.arguments() != null) {
            e.arguments().forEach((tool, expected) -> results.add(arguments(prefix, tool, expected, turn.tools())));
        }
        texts(results, prefix + " · respuesta", turn.reply(), e.replyContains(), e.replyLacks());
        texts(results, prefix + " · entregado", turn.delivered(), e.deliveredContains(), e.deliveredLacks());
        texts(results, prefix + " · aviso al dueño", turn.latestNotice(), e.noticeContains(), e.noticeLacks());
        texts(results, prefix + " · resumen", turn.state().latestDigest(), e.digestContains(), e.digestLacks());
        if (e.state() != null) {
            state(results, prefix + " · estado", e.state(), turn.state());
        }
        return results;
    }

    private CheckResult arguments(String prefix, String tool, Map<String, Object> expected, List<ToolUse> uses) {
        String name = prefix + " · argumentos " + tool;
        List<ToolUse> calls = uses.stream().filter(u -> u.name().equals(tool)).toList();
        if (calls.isEmpty()) {
            return CheckResult.fail(name, tool + " no se llamó");
        }
        for (ToolUse call : calls) {
            if (expected.entrySet().stream().allMatch(entry -> sameValue(argument(entry.getValue()),
                    call.input().get(entry.getKey())))) {
                return CheckResult.pass(name, "entrada " + call.input());
            }
        }
        return CheckResult.fail(name, "esperado " + expected + "; entradas " + calls.stream().map(ToolUse::input).toList());
    }

    private void texts(List<CheckResult> results, String name, String actual, List<String> contains,
                       List<String> lacks) {
        String plain = Text.plain(actual);
        if (contains != null) {
            for (String expected : contains) {
                // "no consta | no me consta": any of the alternatives is enough.
                boolean found = Arrays.stream(expected.split(" \\| "))
                        .anyMatch(alternative -> plain.contains(Text.plain(resolve(alternative.strip()))));
                results.add(CheckResult.of(name + " contiene «" + expected + "»", found, excerpt(actual)));
            }
        }
        if (lacks != null) {
            for (String expected : lacks) {
                String value = resolve(expected);
                results.add(CheckResult.of(name + " no contiene «" + expected + "»", !plain.contains(Text.plain(value)),
                        excerpt(actual)));
            }
        }
    }

    private void state(List<CheckResult> results, String name, ExpectedState expected, ObservedState observed) {
        if (expected.bookingCounts() != null) {
            expected.bookingCounts().forEach((status, count) -> {
                long actual = observed.bookings().stream().filter(b -> b.status().equals(status)).count();
                results.add(CheckResult.of(name + " · reservas " + status, actual == count,
                        "esperadas " + count + ", hay " + actual));
            });
        }
        if (expected.booking() != null) {
            boolean found = observed.bookings().stream().anyMatch(b -> matches(expected.booking(), b));
            results.add(CheckResult.of(name + " · reserva " + expected.booking(), found, "reservas: " + observed.bookings()));
        }
        if (expected.caseCounts() != null) {
            expected.caseCounts().forEach((status, count) -> {
                long actual = observed.cases().stream().filter(c -> c.status().equals(status)).count();
                results.add(CheckResult.of(name + " · casos " + status, actual == count,
                        "esperados " + count + ", hay " + actual));
            });
        }
        if (expected.handoff() != null) {
            boolean found = observed.cases().stream().anyMatch(c -> matches(expected.handoff(), c));
            results.add(CheckResult.of(name + " · caso " + expected.handoff(), found, "casos: " + observed.cases()));
        }
        if (expected.questions() != null) {
            results.add(CheckResult.of(name + " · preguntas", observed.questions() == expected.questions(),
                    "esperadas " + expected.questions() + ", hay " + observed.questions()));
        }
        if (expected.noticeCounts() != null) {
            expected.noticeCounts().forEach((kind, count) -> {
                long actual = observed.notices().stream().filter(n -> n.kind().equals(kind)).count();
                results.add(CheckResult.of(name + " · avisos " + kind, actual == count,
                        "esperados " + count + ", hay " + actual));
            });
        }
        if (expected.notice() != null) {
            boolean found = observed.notices().stream().anyMatch(n -> matches(expected.notice(), n));
            results.add(CheckResult.of(name + " · aviso " + expected.notice(), found, "avisos: " + observed.notices()
                    .stream().map(n -> n.kind() + "/" + n.status() + "/" + n.reason()).toList()));
        }
        if (expected.digests() != null) {
            results.add(CheckResult.of(name + " · resumenes", observed.digests() == expected.digests(),
                    "esperados " + expected.digests() + ", hay " + observed.digests()));
        }
    }

    private boolean matches(Map<String, Object> expected, ObservedBooking b) {
        return expected.entrySet().stream().allMatch(entry -> {
            Object want = resolve(entry.getValue());
            return switch (entry.getKey()) {
                case "estado" -> sameValue(want, b.status());
                case "fecha" -> Objects.equals(dates.apply(String.valueOf(want)), b.date());
                case "turno" -> sameValue(want, b.shift());
                case "hora" -> sameValue(want, b.time());
                case "personas" -> sameValue(want, b.partySize());
                case "codigo" -> sameValue(want, b.code());
                case "contacto" -> sameValue(want, b.contactName());
                default -> false;
            };
        });
    }

    private static boolean matches(Map<String, Object> expected, ObservedCase c) {
        return expected.entrySet().stream().allMatch(entry -> switch (entry.getKey()) {
            case "categoria" -> sameValue(entry.getValue(), c.category());
            case "estado" -> sameValue(entry.getValue(), c.status());
            default -> false;
        });
    }

    private static boolean matches(Map<String, Object> expected, ObservedNotice n) {
        return expected.entrySet().stream().allMatch(entry -> switch (entry.getKey()) {
            case "tipo" -> sameValue(entry.getValue(), n.kind());
            case "estado" -> sameValue(entry.getValue(), n.status());
            case "motivo" -> sameValue(entry.getValue(), n.reason());
            default -> false;
        });
    }

    /** {@code value} with its {@code $alias.codigo} variables replaced. */
    Object resolve(Object value) {
        if (!(value instanceof String text)) {
            return value;
        }
        Matcher m = VARIABLE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String code = codes.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(code == null ? m.group() : code));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * An expected argument: its variables resolved and, if it is a relative date in braces
     * ({@code "{+1}"}, {@code "{proximo_sabado}"}), that date in the business's calendar as YYYY-MM-DD.
     */
    private Object argument(Object value) {
        Object resolved = resolve(value);
        if (resolved instanceof String text) {
            Matcher date = DATE_ARGUMENT.matcher(text.strip());
            if (date.matches()) {
                return dates.apply(date.group(1)).toString();
            }
        }
        return resolved;
    }

    private String resolve(String value) {
        return String.valueOf(resolve((Object) value));
    }

    /** The same value, comparing as text without case or surrounding spaces (YAML gives 6, the input 6). */
    private static boolean sameValue(Object expected, Object actual) {
        return actual != null && Text.plain(String.valueOf(expected)).equals(Text.plain(String.valueOf(actual)));
    }

    private static boolean isSubsequence(List<String> wanted, List<String> called) {
        int at = 0;
        for (String tool : called) {
            if (at < wanted.size() && wanted.get(at).equals(tool)) {
                at++;
            }
        }
        return at == wanted.size();
    }

    private static String excerpt(String text) {
        if (text == null) {
            return "(nada)";
        }
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= 200 ? "«" + oneLine + "»" : "«" + oneLine.substring(0, 200) + "…»";
    }
}
