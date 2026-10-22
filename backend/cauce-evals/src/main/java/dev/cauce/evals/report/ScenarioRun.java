package dev.cauce.evals.report;

import dev.cauce.evals.check.CheckResult;
import dev.cauce.evals.scenario.Scenario;
import java.util.List;

/**
 * One repetition of one scenario: every check it produced, in order, and the transcript excerpt
 * the report shows for a failure.
 */
public record ScenarioRun(Scenario scenario, int repetition, List<CheckResult> results, String transcript) {

    public ScenarioRun {
        results = List.copyOf(results);
    }

    /** A repetition passes when no counted check failed; with no counted check it says nothing. */
    public boolean passed() {
        return results.stream().filter(CheckResult::counts).allMatch(r -> r.outcome() == CheckResult.Outcome.PASS);
    }

    public boolean counted() {
        return results.stream().anyMatch(CheckResult::counts);
    }

    public boolean harnessError() {
        return results.stream().anyMatch(r -> r.outcome() == CheckResult.Outcome.HARNESS_ERROR);
    }
}
