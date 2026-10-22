package dev.cauce.evals.judge;

import java.util.List;

/**
 * Grades one turn against its written rubric (ADR 0009, E6): only for what the records cannot
 * answer — tone, not promising, not inventing. A verdict it cannot read is inconclusive, and an
 * inconclusive verdict never passes.
 */
public interface Judge {

    Judgement judge(Case turn);

    /** The provider and model doing the judging, for the report and the history. */
    String describe();

    /**
     * One turn as the judge reads it.
     *
     * @param tools each call of the turn as {@code name: result summary}
     */
    record Case(String message, List<String> tools, String reply, String rubric) {

        public Case {
            tools = List.copyOf(tools);
        }
    }

    /** What the judge concluded, and why. */
    record Judgement(Verdict verdict, String reason) {
    }

    enum Verdict {
        MEETS,
        FAILS,
        INCONCLUSIVE
    }
}
