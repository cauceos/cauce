package dev.cauce.evals.run;

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
 * @param agentProvider and {@code agentModel}: the customer agent's model ({@code evals.proveedor},
 *        {@code evals.modelo}, default anthropic / claude-sonnet-5-5, as the pack's demo)
 * @param judgeProvider the judge's provider ({@code evals.juez.proveedor}); by default the first
 *        registered one that is not the agent's
 * @param judgeModel the judge's model ({@code evals.juez.modelo}); without it there is no judge
 * @param rehearsal a run against {@link RehearsalProvider} instead of the model ({@code evals.simulacro}):
 *        no tokens, nothing written to the history
 * @param elidePastLookups the customer agent leaves earlier turns' lookups out of its context
 *        ({@code evals.r1=true}; deferred R1, off by default): the run is marked R1 in the report
 */
public record EvalSettings(Path repoRoot, String pack, List<String> tasks, List<String> scenarioIds,
                           int repetitions, double limitUsd, boolean confirmed, double warningThreshold,
                           String agentProvider, String agentModel, Optional<String> judgeProvider,
                           Optional<String> judgeModel, boolean rehearsal, boolean elidePastLookups) {

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
                property("evals.proveedor").orElse("anthropic"),
                property("evals.modelo").orElse("claude-sonnet-5-5"),
                property("evals.juez.proveedor"),
                property("evals.juez.modelo"),
                property("evals.simulacro").map(Boolean::parseBoolean).orElse(false),
                property("evals.r1").map(Boolean::parseBoolean).orElse(false));
    }

    public Path packDir() {
        return repoRoot.resolve("examples").resolve(pack);
    }

    public Path evalsDir() {
        return packDir().resolve("evals");
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
        return elidePastLookups ? selected + " · R1: consultas pasadas omitidas" : selected;
    }

    private static List<String> list(String name) {
        return property(name).map(value -> Arrays.stream(value.split(",")).map(String::strip)
                .filter(item -> !item.isEmpty()).toList()).orElse(List.of());
    }

    private static Optional<String> property(String name) {
        return Optional.ofNullable(System.getProperty(name)).filter(value -> !value.isBlank());
    }
}
