package dev.cauce.evals.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Whether a run says anything about the agent (ADR 0009, E7). A repetition whose turn failed to
 * execute is left out of the rates, so a run where the model was never reached would report 100 %
 * over nothing. A run is invalid — it fails and nothing goes to the history — when a scenario has no
 * valid repetition, or when the share of repetitions with an execution failure is over the
 * threshold. A provider that refuses the key stops the run at the first turn instead.
 */
public final class RunValidity {

    /** The share of repetitions with an execution failure a run tolerates by default. */
    public static final double DEFAULT_MAX_EXECUTION_FAILURES = 0.10;

    private RunValidity() {
    }

    /**
     * Why the run is invalid, or nothing. Only repetitions of conversations count: a scenario whose
     * every repetition failed to execute has no valid one.
     */
    public static List<String> reasons(List<ScenarioRun> runs, double maxExecutionFailures) {
        List<String> reasons = new ArrayList<>();
        Map<String, int[]> byScenario = new LinkedHashMap<>();
        for (ScenarioRun run : runs) {
            int[] tally = byScenario.computeIfAbsent(run.scenario().id(), id -> new int[2]);
            tally[0]++;
            if (run.executionFailure()) {
                tally[1]++;
            }
        }
        int repetitions = 0;
        int failed = 0;
        for (Map.Entry<String, int[]> scenario : byScenario.entrySet()) {
            int[] tally = scenario.getValue();
            repetitions += tally[0];
            failed += tally[1];
            if (tally[1] == tally[0]) {
                reasons.add(scenario.getKey() + ": ninguna repetición válida (" + tally[1] + " de " + tally[0]
                        + " con fallo de ejecución)");
            }
        }
        if (repetitions > 0 && (double) failed / repetitions > maxExecutionFailures) {
            reasons.add(0, failed + " de " + repetitions + " repeticiones con fallo de ejecución ("
                    + Aggregation.percent((double) failed / repetitions) + "), por encima del umbral de "
                    + Aggregation.percent(maxExecutionFailures));
        }
        return reasons;
    }

    /**
     * Whether an execution failure is the provider refusing the credentials (HTTP 401): every later
     * turn would fail the same way, so the run stops at the first one.
     */
    public static boolean refusedCredentials(String executionFailure) {
        return executionFailure != null && (executionFailure.contains("LlmAuthenticationException")
                || executionFailure.contains("HTTP 401"));
    }
}
