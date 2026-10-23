package dev.cauce.evals.report;

import dev.cauce.evals.check.CheckResult;
import dev.cauce.evals.check.CheckResult.Outcome;
import dev.cauce.evals.check.GeneralRules;
import dev.cauce.evals.cost.PriceTable;
import dev.cauce.evals.report.RunSummary.ScenarioRate;
import dev.cauce.evals.scenario.Scenario;
import dev.cauce.evals.scenario.Scenario.Level;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Turns the repetitions of a run into rates and a verdict (ADR 0009, E7). A repetition passes when
 * none of its counted checks failed; a scenario's rate is the share of its repetitions that passed.
 * The general rules (G1, the grounded menu) are rated over every turn they applied to. Blocking:
 * a blocking scenario, or a general rule, under 100 %. Warning: any other scenario under the
 * threshold. Neither a rubric pending a judge nor a harness error ever counts as passed: a run
 * without a judge is a run of the deterministic checks. Each scenario's tokens are the sum of its
 * repetitions', priced with the agent's price when the table has one. A run that says nothing
 * about the agent — a scenario with no valid repetition, or too many execution failures
 * ({@link RunValidity}) — is invalid, and an invalid run blocks.
 */
public final class Aggregation {

    /** Everything about a run that is not its results; {@code agentPrice} is null when the table has none. */
    public record Meta(String date, String commit, boolean dirtyTree, String agentModel, String judge,
                       String calibration, String promptFootprint, int repetitions, String selection, boolean complete,
                       RunSummary.Tokens tokens, Double costUsd, String priceVersion, PriceTable.Price agentPrice) {
    }

    private Aggregation() {
    }

    public static RunSummary summarize(List<ScenarioRun> runs, List<Scenario> coveredByIt, double warningThreshold,
                                       Meta meta) {
        return summarize(runs, coveredByIt, warningThreshold, RunValidity.DEFAULT_MAX_EXECUTION_FAILURES, meta);
    }

    /** As {@link #summarize(List, List, double, Meta)}, with the run's own share of tolerated execution failures. */
    public static RunSummary summarize(List<ScenarioRun> runs, List<Scenario> coveredByIt, double warningThreshold,
                                       double maxExecutionFailures, Meta meta) {
        Map<String, List<ScenarioRun>> byScenario = new LinkedHashMap<>();
        runs.forEach(run -> byScenario.computeIfAbsent(run.scenario().id(), id -> new ArrayList<>()).add(run));

        List<ScenarioRate> scenarios = new ArrayList<>();
        List<String> blocking = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int pendingJudge = 0;
        for (List<ScenarioRun> reps : byScenario.values()) {
            Scenario scenario = reps.get(0).scenario();
            Double rate = rate(reps);
            Map<String, Double> checks = new LinkedHashMap<>();
            Map<String, int[]> tally = new LinkedHashMap<>();
            int withoutJudge = 0;
            for (ScenarioRun rep : reps) {
                for (CheckResult r : rep.results()) {
                    if (r.outcome() == Outcome.NO_JUDGE) {
                        withoutJudge++;
                    }
                    if (r.counts()) {
                        int[] t = tally.computeIfAbsent(r.name(), n -> new int[2]);
                        t[r.outcome() == Outcome.PASS ? 0 : 1]++;
                    }
                }
            }
            tally.forEach((name, t) -> checks.put(name, (double) t[0] / (t[0] + t[1])));
            String status;
            if (rate == null) {
                status = "sin comprobaciones";
            } else if (scenario.level() == Level.BLOCKING && rate < 1.0) {
                status = "falla";
                blocking.add(scenario.id() + " " + percent(rate));
            } else if (scenario.level() == Level.WARNING && rate < warningThreshold) {
                status = "aviso";
                warnings.add(scenario.id() + " " + percent(rate) + " (umbral " + percent(warningThreshold) + ")");
            } else {
                status = "ok";
            }
            for (ScenarioRun rep : reps) {
                rep.results().stream().filter(r -> r.outcome() == Outcome.EXECUTION_FAILURE).forEach(r ->
                        warnings.add(scenario.id() + " · repetición " + rep.repetition() + " · " + r.name()
                                + ", fuera de la tasa del agente: " + r.detail()));
            }
            if (reps.stream().anyMatch(ScenarioRun::harnessError)) {
                warnings.add(scenario.id() + ": error del arnés en alguna repetición (ver informe)");
            }
            pendingJudge += withoutJudge;
            RunSummary.ScenarioTokens tokens = reps.stream().map(ScenarioRun::tokens).filter(java.util.Objects::nonNull)
                    .reduce(RunSummary.ScenarioTokens::plus).orElse(null);
            Double cost = tokens == null || meta.agentPrice() == null ? null
                    : meta.agentPrice().cost(tokens.input(), tokens.cacheRead(), tokens.cacheWrite(), tokens.output());
            scenarios.add(new ScenarioRate(scenario.id(), scenario.task(), level(scenario), rate, reps.size(), checks,
                    withoutJudge, status, tokens, cost));
        }
        for (Scenario covered : coveredByIt) {
            scenarios.add(new ScenarioRate(covered.id(), covered.task(), level(covered), null, 0, null, 0,
                    "cubierto por " + covered.coveredBy(), null, null));
        }

        if (pendingJudge > 0) {
            warnings.add(pendingJudge + " comprobaciones de rúbrica pendientes de juez: no cuentan en ninguna tasa");
        }
        // One table per task in the report: the covered scenarios join their task, in file order.
        scenarios.sort(java.util.Comparator.comparing(ScenarioRate::task));

        Map<String, Double> rules = new LinkedHashMap<>();
        for (String rule : List.of(GeneralRules.G1, GeneralRules.GROUNDED)) {
            Double ruleRate = checkRate(runs, r -> r.name().endsWith(rule));
            if (ruleRate != null) {
                rules.put(rule, ruleRate);
                if (ruleRate < 1.0) {
                    blocking.add(rule + " " + percent(ruleRate));
                }
            }
        }
        Map<String, Double> tasks = new LinkedHashMap<>();
        Map<String, List<ScenarioRun>> byTask = new LinkedHashMap<>();
        runs.forEach(run -> byTask.computeIfAbsent(run.scenario().task(), t -> new ArrayList<>()).add(run));
        byTask.forEach((task, reps) -> {
            Double rate = rate(reps);
            if (rate != null) {
                tasks.put(task, rate);
            }
        });
        Double overall = rate(runs);
        return new RunSummary(meta.date(), meta.commit(), meta.dirtyTree(), meta.agentModel(), meta.judge(),
                meta.calibration(), meta.promptFootprint(), meta.repetitions(), meta.selection(), meta.complete(),
                meta.tokens(), meta.costUsd(), meta.priceVersion(), overall == null ? 0.0 : overall, rules, tasks,
                scenarios, blocking, warnings, RunValidity.reasons(runs, maxExecutionFailures));
    }

    /** Whether the run blocks: invalid, or a blocking scenario or a general rule under 100 %. */
    public static boolean blocks(RunSummary summary) {
        return !summary.valid() || !summary.blockingFailures().isEmpty();
    }

    private static Double rate(List<ScenarioRun> reps) {
        List<ScenarioRun> counted = reps.stream().filter(ScenarioRun::counted).toList();
        if (counted.isEmpty()) {
            return null;
        }
        return (double) counted.stream().filter(ScenarioRun::passed).count() / counted.size();
    }

    private static Double checkRate(List<ScenarioRun> runs, Predicate<CheckResult> which) {
        long pass = 0;
        long fail = 0;
        for (ScenarioRun run : runs) {
            for (CheckResult r : run.results()) {
                if (which.test(r) && r.counts()) {
                    if (r.outcome() == Outcome.PASS) {
                        pass++;
                    } else {
                        fail++;
                    }
                }
            }
        }
        return pass + fail == 0 ? null : (double) pass / (pass + fail);
    }

    private static String level(Scenario scenario) {
        return scenario.level() == Level.BLOCKING ? "bloqueante" : "aviso";
    }

    static String percent(double rate) {
        return Math.round(rate * 100) + " %";
    }
}
