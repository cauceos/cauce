package dev.cauce.evals.report;

import dev.cauce.evals.check.CheckResult;
import dev.cauce.evals.scenario.Scenario;
import java.util.List;

/**
 * One repetition of one scenario: every check it produced, in order, the transcript excerpt the
 * report shows for a failure, and the agent's tokens it spent (null when it drove no model: a
 * daily summary).
 */
public record ScenarioRun(Scenario scenario, int repetition, List<CheckResult> results, String transcript,
                          RunSummary.ScenarioTokens tokens) {

    public ScenarioRun {
        results = List.copyOf(results);
    }

    public ScenarioRun(Scenario scenario, int repetition, List<CheckResult> results, String transcript) {
        this(scenario, repetition, results, transcript, null);
    }

    /** A repetition passes when no counted check failed; with no counted check it says nothing. */
    public boolean passed() {
        return results.stream().filter(CheckResult::counts).allMatch(r -> r.outcome() == CheckResult.Outcome.PASS);
    }

    /** Whether it counts towards a rate: not when a turn failed to execute, which says nothing of the agent. */
    public boolean counted() {
        return !executionFailure() && results.stream().anyMatch(CheckResult::counts);
    }

    public boolean executionFailure() {
        return results.stream().anyMatch(r -> r.outcome() == CheckResult.Outcome.EXECUTION_FAILURE);
    }

    public boolean harnessError() {
        return results.stream().anyMatch(r -> r.outcome() == CheckResult.Outcome.HARNESS_ERROR);
    }
}
