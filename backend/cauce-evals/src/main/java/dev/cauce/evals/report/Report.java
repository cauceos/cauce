package dev.cauce.evals.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.cauce.evals.check.CheckResult;
import dev.cauce.evals.check.GeneralRules;
import dev.cauce.evals.report.RunSummary.ScenarioRate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The report of a run (ADR 0009, E11, E12): the summary as JSON for the history, and a Markdown
 * page with a table per task, each scenario's rate, the change against the previous run, and one
 * transcript excerpt per failing check.
 */
public final class Report {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private Report() {
    }

    public static String json(RunSummary summary) {
        try {
            return JSON.writeValueAsString(summary);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The most recent summary in {@code history} (files sort by date), if any. */
    public static Optional<RunSummary> previous(Path history) {
        return previous(history, null);
    }

    /**
     * The summary a run of {@code agentModel} (provider/model) is compared with: the most recent of the
     * same model, or, the first time a model runs, the most recent of any. The report names it.
     */
    public static Optional<RunSummary> previous(Path history, String agentModel) {
        if (!Files.isDirectory(history)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(history)) {
            List<RunSummary> newestFirst = files.filter(f -> f.toString().endsWith(".json"))
                    .sorted(Comparator.comparing((Path f) -> f.getFileName().toString()).reversed())
                    .map(Report::read)
                    .toList();
            return newestFirst.stream().filter(s -> agentModel != null && agentModel.equals(s.agentModel()))
                    .findFirst()
                    .or(() -> newestFirst.stream().findFirst());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static RunSummary read(Path file) {
        try {
            return JSON.readValue(file.toFile(), RunSummary.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    /**
     * The summary for the console, in plain columns: one line per scenario, then the checks that did
     * not always pass, what blocks, and the tokens and cost. The transcripts stay in the Markdown page.
     */
    public static String console(RunSummary s) {
        StringBuilder out = new StringBuilder();
        out.append("Evals · ").append(s.selection()).append(" · N = ").append(s.repetitions()).append(" · ")
                .append(s.agentModel()).append(" · juez: ").append(s.judge()).append("\n\n");
        out.append(String.format("%-9s %-11s %6s  %-5s %s\n", "Escenario", "Nivel", "Tasa", "Rep.", "Estado"));
        for (ScenarioRate rate : s.scenarios()) {
            out.append(String.format("%-9s %-11s %6s  %-5d %s\n", rate.id(), rate.level(),
                    rate.rate() == null ? "—" : Aggregation.percent(rate.rate()), rate.repetitions(), rate.status()));
        }
        out.append("\nTasa global ").append(Aggregation.percent(s.overallRate())).append("\n");
        for (ScenarioRate rate : s.scenarios()) {
            if (rate.checks() == null) {
                continue; // covered by an IT: nothing ran
            }
            rate.checks().entrySet().stream().filter(c -> c.getValue() < 1.0)
                    .forEach(c -> out.append("  ").append(rate.id()).append(" · ").append(c.getKey()).append(": ")
                            .append(Aggregation.percent(c.getValue())).append("\n"));
        }
        s.invalidity().forEach(reason -> out.append("EJECUCIÓN INVÁLIDA: ").append(reason).append("\n"));
        out.append(s.blockingFailures().isEmpty() ? "Sin bloqueantes fallidos.\n"
                : "Bloqueantes por debajo del 100 %: " + String.join(", ", s.blockingFailures()) + "\n");
        s.warnings().forEach(w -> out.append("Aviso: ").append(w).append("\n"));
        if (s.tokens() != null) {
            RunSummary.Tokens t = s.tokens();
            out.append(String.format("Tokens: %d de entrada (%s leídos de caché), %d de salida, en %d turnos de modelo"
                            + " (%d de entrada por turno)\n",
                    t.input(), t.input() == 0 ? "0 %" : Aggregation.percent((double) t.cacheRead() / t.input()),
                    t.output(), t.modelTurns(), perTurn(t)));
            out.append(String.format("Escritura en caché: %d tokens, de ellos %d para 1 hora\n", t.cacheWrite(),
                    t.cacheWriteLong()));
        }
        out.append("Coste: ").append(s.costUsd() == null ? "sin precio en precios.yaml"
                : String.format("%.4f US$", s.costUsd())).append("\n");
        return out.toString();
    }

    /** Input tokens per model turn: what eliding past lookups (R1) is meant to lower. */
    static long perTurn(RunSummary.Tokens t) {
        return t.modelTurns() == 0 ? 0 : Math.round((double) t.input() / t.modelTurns());
    }

    public static String markdown(RunSummary s, Optional<RunSummary> previous, List<ScenarioRun> runs) {
        StringBuilder md = new StringBuilder();
        md.append("# Evals · ").append(s.date()).append("\n\n");
        md.append("- Modelo: `").append(s.agentModel()).append("` · juez: `").append(s.judge()).append("` (")
                .append(s.calibration()).append(")\n");
        md.append("- Commit `").append(s.commit()).append(s.dirtyTree() ? "` con cambios sin commitear" : "`")
                .append(" · huella del prompt `").append(abbreviate(s.promptFootprint())).append("`\n");
        previous.ifPresent(p -> md.append("- Comparada con `").append(p.date()).append("` · `").append(p.agentModel())
                .append("`").append(java.util.Objects.equals(s.agentModel(), p.agentModel()) ? "" : " (otro modelo)").append("\n"));
        md.append("- N = ").append(s.repetitions()).append(" · selección: ").append(s.selection())
                .append(s.complete() ? "" : " · **incompleta: se alcanzó el límite de coste**").append("\n");
        if (s.tokens() != null) {
            md.append("- Tokens: ").append(s.tokens().input()).append(" de entrada (").append(s.tokens().cacheRead())
                    .append(" leídos de caché), ").append(s.tokens().output()).append(" de salida, en ")
                    .append(s.tokens().modelTurns()).append(" turnos (").append(perTurn(s.tokens()))
                    .append(" de entrada por turno); juez ").append(s.tokens().judgeInput()).append(" / ")
                    .append(s.tokens().judgeOutput()).append("\n");
        }
        md.append("- Coste estimado: ").append(s.costUsd() == null ? "sin precio en precios.yaml"
                : String.format("%.4f US$ (precios %s)", s.costUsd(), s.priceVersion())).append("\n\n");

        if (!s.valid()) {
            md.append("**Ejecución inválida: no dice nada del agente y no va al historial.**\n");
            s.invalidity().forEach(reason -> md.append("- ").append(reason).append("\n"));
            md.append("\n");
        }
        md.append(s.blockingFailures().isEmpty() ? "**Sin bloqueantes fallidos.**\n\n"
                : "**Bloqueantes por debajo del 100 %:** " + String.join(", ", s.blockingFailures()) + "\n\n");
        if (!s.warnings().isEmpty()) {
            md.append("Avisos:\n");
            s.warnings().forEach(w -> md.append("- ").append(w).append("\n"));
            md.append("\n");
        }
        md.append("Tasa global ").append(Aggregation.percent(s.overallRate()))
                .append(delta(s.overallRate(), previous.map(RunSummary::overallRate))).append("\n\n");
        for (String rule : List.of(GeneralRules.G1, GeneralRules.GROUNDED)) {
            Double rate = s.generalRules().get(rule);
            md.append("- ").append(rule).append(": ").append(rate == null ? "no aplicó en ningún turno"
                    : Aggregation.percent(rate) + delta(rate, previous.map(p -> p.generalRules().get(rule))))
                    .append("\n");
        }
        md.append("\n");

        Map<String, ScenarioRate> before = previous.map(p -> p.scenarios().stream()
                .collect(java.util.stream.Collectors.toMap(ScenarioRate::id, r -> r, (a, b) -> a)))
                .orElse(Map.of());
        String task = null;
        for (ScenarioRate rate : s.scenarios()) {
            if (!rate.task().equals(task)) {
                task = rate.task();
                Double taskRate = s.tasks().get(task);
                md.append("\n## ").append(task).append(taskRate == null ? "" : " · " + Aggregation.percent(taskRate))
                        .append("\n\n| Escenario | Nivel | Tasa | Repeticiones | Estado | Coste por repetición |\n"
                                + "|---|---|---|---|---|---|\n");
            }
            ScenarioRate old = before.get(rate.id());
            md.append("| ").append(rate.id()).append(" | ").append(rate.level()).append(" | ")
                    .append(rate.rate() == null ? "—" : Aggregation.percent(rate.rate())
                            + delta(rate.rate(), Optional.ofNullable(old).map(ScenarioRate::rate)))
                    .append(" | ").append(rate.repetitions()).append(" | ").append(rate.status()).append(" | ")
                    .append(costPerRepetition(rate)).append(" |\n");
        }

        md.append("\n## Comprobaciones que fallaron\n");
        boolean any = false;
        for (ScenarioRun run : runs) {
            List<CheckResult> failed = run.results().stream()
                    .filter(r -> r.outcome() == CheckResult.Outcome.FAIL || r.outcome() == CheckResult.Outcome.HARNESS_ERROR
                            || r.outcome() == CheckResult.Outcome.INCONCLUSIVE
                            || r.outcome() == CheckResult.Outcome.EXECUTION_FAILURE)
                    .toList();
            if (failed.isEmpty()) {
                continue;
            }
            any = true;
            md.append("\n### ").append(run.scenario().id()).append(" · repetición ").append(run.repetition()).append("\n\n");
            failed.forEach(r -> md.append("- ").append(r.outcome()).append(" · ").append(r.name()).append(": ")
                    .append(r.detail()).append("\n"));
            md.append("\n```\n").append(run.transcript()).append("\n```\n");
        }
        if (!any) {
            md.append("\nNinguna.\n");
        }
        return md.toString();
    }

    /** What one repetition of the scenario cost on average: one conversation of the agent. */
    static String costPerRepetition(ScenarioRate rate) {
        if (rate.costUsd() == null || rate.repetitions() == 0) {
            return "—";
        }
        return String.format("%.4f US$", rate.costUsd() / rate.repetitions());
    }

    private static String delta(double now, Optional<Double> before) {
        if (before.isEmpty() || before.get() == null) {
            return "";
        }
        long points = Math.round((now - before.get()) * 100);
        return points == 0 ? " (=)" : " (" + (points > 0 ? "+" : "") + points + ")";
    }

    private static String abbreviate(String footprint) {
        return footprint == null || footprint.length() < 12 ? String.valueOf(footprint) : footprint.substring(0, 12);
    }
}
