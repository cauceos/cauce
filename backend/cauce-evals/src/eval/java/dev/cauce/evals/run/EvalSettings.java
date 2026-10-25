package dev.cauce.evals.run;

import dev.cauce.evals.report.RunValidity;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * What one run is asked to do, from the {@code -Pevals.*} properties the Gradle task passes as
 * system properties (ADR 0009, E7, E10).
 *
 * @param pack the sector pack under {@code examples/} ({@code evals.paquete}, default hosteleria)
 * @param tasks only the scenarios of the task files with these prefixes ({@code evals.tarea=03} or
 *        {@code evals.tarea=01,02})
 * @param scenarioIds only these scenarios ({@code evals.escenarios=H3.10,H3.11})
 * @param repetitions N ({@code evals.n}, default 5)
 * @param limitUsd the estimate that needs confirmation ({@code evals.limite}, default 10 US$)
 * @param confirmed the estimate over the limit is accepted ({@code evals.confirmar=true})
 * @param warningThreshold the rate under which a warning scenario warns ({@code evals.umbral}, 0.9)
 * @param maxExecutionFailures the share of repetitions with an execution failure over which the run
 *        is invalid ({@code evals.umbral_fallos}, 0.10)
 * @param agentProvider and {@code agentModel}: the customer agent's model ({@code evals.proveedor},
 *        {@code evals.modelo}, default anthropic / claude-sonnet-5-5, as the pack's demo)
 * @param judgeProvider the judge's provider ({@code evals.juez.proveedor}); by default the first
 *        registered one that is not the agent's
 * @param judgeModel the judge's model ({@code evals.juez.modelo}); without it there is no judge
 * @param rehearsal a run against {@link RehearsalProvider} instead of the model ({@code evals.simulacro}):
 *        no tokens, nothing written to the history
 * @param elidePastLookups the customer agent leaves earlier turns' lookups out of its context
 *        ({@code evals.r1=true}; deferred R1, off by default): the run is marked R1 in the report
 * @param cacheRetention the customer agent's prompt cache retention when it is not the pack's
 *        ({@code evals.retencion=SHORT|LONG}); empty: the pack's {@code retencion-cache.json}
 * @param forced a full battery runs from a tree behind {@code origin/main} or with uncommitted
 *        changes to the pack ({@code evals.forzar=true}); refused otherwise
 */
public record EvalSettings(Path repoRoot, String pack, List<String> tasks, List<String> scenarioIds,
                           int repetitions, double limitUsd, boolean confirmed, double warningThreshold,
                           double maxExecutionFailures,
                           String agentProvider, String agentModel, Optional<String> judgeProvider,
                           Optional<String> judgeModel, boolean rehearsal, boolean elidePastLookups,
                           Optional<String> cacheRetention, boolean forced) {

    public static EvalSettings fromSystemProperties() {
        String root = System.getProperty("evals.raiz");
        if (root == null) {
            throw new IllegalStateException("Run the evals through ./gradlew :cauce-evals:eval (evals.raiz is unset)");
        }
        return new EvalSettings(Path.of(root), property("evals.paquete").orElse("hosteleria"),
                list("evals.tarea"),
                list("evals.escenarios"),
                property("evals.n").map(Integer::parseInt).orElse(5),
                property("evals.limite").map(Double::parseDouble).orElse(10.0),
                property("evals.confirmar").map(Boolean::parseBoolean).orElse(false),
                property("evals.umbral").map(Double::parseDouble).orElse(0.9),
                property("evals.umbral_fallos").map(Double::parseDouble)
                        .orElse(RunValidity.DEFAULT_MAX_EXECUTION_FAILURES),
                property("evals.proveedor").orElse("anthropic"),
                property("evals.modelo").orElse("claude-sonnet-5-5"),
                property("evals.juez.proveedor"),
                property("evals.juez.modelo"),
                property("evals.simulacro").map(Boolean::parseBoolean).orElse(false),
                property("evals.r1").map(Boolean::parseBoolean).orElse(false),
                property("evals.retencion").map(value -> value.toUpperCase(java.util.Locale.ROOT)),
                property("evals.forzar").map(Boolean::parseBoolean).orElse(false));
    }

    /** Whether the run is the whole battery: neither tasks nor scenarios selected. */
    public boolean fullBattery() {
        return tasks.isEmpty() && scenarioIds.isEmpty();
    }

    public Path packDir() {
        return repoRoot.resolve("examples").resolve(pack);
    }

    public Path evalsDir() {
        return packDir().resolve("evals");
    }

    /** The committed history: one summary per run that was moved into it. */
    public Path historyDir() {
        return evalsDir().resolve("historial");
    }

    /**
     * Where a run writes its summary: ignored by git, so it never blocks a checkout; moving it into
     * {@link #historyDir()} is the explicit step of committing it.
     */
    public Path newHistoryDir() {
        return evalsDir().resolve("historial-nuevo");
    }

    /** How the scenarios were selected, for the report, and whether the run measures R1. */
    public String selection() {
        String selected;
        if (!scenarioIds.isEmpty()) {
            selected = String.join(",", scenarioIds);
        } else if (!tasks.isEmpty()) {
            selected = (tasks.size() == 1 ? "tarea " : "tareas ") + String.join(",", tasks);
        } else {
            selected = "batería completa";
        }
        String withRetention = cacheRetention.map(r -> selected + " · caché " + r + " (no la del paquete)")
                .orElse(selected);
        return elidePastLookups ? withRetention + " · R1: consultas pasadas omitidas" : withRetention;
    }

    private static List<String> list(String name) {
        return property(name).map(value -> Arrays.stream(value.split(",")).map(String::strip)
                .filter(item -> !item.isEmpty()).toList()).orElse(List.of());
    }

    private static Optional<String> property(String name) {
        return Optional.ofNullable(System.getProperty(name)).filter(value -> !value.isBlank());
    }
}
