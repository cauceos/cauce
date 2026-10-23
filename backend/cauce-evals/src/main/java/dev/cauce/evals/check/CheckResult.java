package dev.cauce.evals.check;

/**
 * The outcome of one check of one turn (or of a scenario's action).
 *
 * @param name a stable name within the scenario: {@code turno 2 · herramientas}
 * @param detail what was expected and what was found, for the report
 */
public record CheckResult(String name, Outcome outcome, String detail) {

    /** What a check concluded. Only PASS and FAIL count in a rate (ADR 0009, E6, E7). */
    public enum Outcome {
        PASS,
        FAIL,
        /** The rule does not apply to this turn (G1 on a turn that does not talk about the menu). */
        NOT_APPLICABLE,
        /** A rubric pending a calibrated judge: reported, never counted as passed. */
        NO_JUDGE,
        /** The judge's answer could not be read: never counted as passed. */
        INCONCLUSIVE,
        /** The harness could not establish the facts (the trace and the audit disagree). */
        HARNESS_ERROR,
        /**
         * The turn's invocation failed and the person got the fallback reply: a fault of the run
         * (the provider, a timeout, the platform), not of the agent. Its repetition stays out of the
         * agent's rates, and the report names the cause.
         */
        EXECUTION_FAILURE
    }

    public static CheckResult pass(String name, String detail) {
        return new CheckResult(name, Outcome.PASS, detail);
    }

    public static CheckResult fail(String name, String detail) {
        return new CheckResult(name, Outcome.FAIL, detail);
    }

    public static CheckResult of(String name, boolean passed, String detail) {
        return new CheckResult(name, passed ? Outcome.PASS : Outcome.FAIL, detail);
    }

    /** Whether this result counts towards a rate. */
    public boolean counts() {
        return outcome == Outcome.PASS || outcome == Outcome.FAIL;
    }
}
