package dev.cauce.evals.cost;

import java.util.Optional;

/**
 * What a run is expected to cost before it starts (ADR 0009, E10): the model turns it will drive
 * times the tokens a turn took in the previous run (or {@link #DEFAULT_INPUT_PER_TURN} and
 * {@link #DEFAULT_OUTPUT_PER_TURN} the first time), priced uncached — a ceiling, since part of the
 * input will come from the cache — plus the judge's calls when its price is known.
 *
 * @param usd the estimate, or empty when the agent's model has no price in the table
 */
public record CostEstimate(int modelTurns, int judgeCalls, long inputTokens, long outputTokens, Optional<Double> usd,
                           String basis) {

    /** Measured on the 2026-10-22 test: ~2 calls per turn of ~4-6k tokens in a short conversation. */
    public static final long DEFAULT_INPUT_PER_TURN = 10_000;
    public static final long DEFAULT_OUTPUT_PER_TURN = 250;
    /** A judge call: instructions, one turn and a short verdict. */
    static final long JUDGE_INPUT = 1_500;
    static final long JUDGE_OUTPUT = 80;

    /**
     * @param inputPerTurn and {@code outputPerTurn} from the previous run, or null for the defaults
     */
    public static CostEstimate of(int modelTurns, int judgeCalls, Long inputPerTurn, Long outputPerTurn,
                                  Optional<PriceTable.Price> agentPrice, Optional<PriceTable.Price> judgePrice) {
        long in = inputPerTurn == null ? DEFAULT_INPUT_PER_TURN : inputPerTurn;
        long out = outputPerTurn == null ? DEFAULT_OUTPUT_PER_TURN : outputPerTurn;
        String basis = inputPerTurn == null ? "valores por defecto por turno (" + in + " de entrada, " + out
                + " de salida)" : "tokens por turno de la ejecución anterior (" + in + " / " + out + ")";
        long input = in * modelTurns;
        long output = out * modelTurns;
        Optional<Double> usd = agentPrice.map(price -> price.cost(input, 0, 0, output)
                + judgePrice.map(j -> j.cost(JUDGE_INPUT * judgeCalls, 0, 0, JUDGE_OUTPUT * judgeCalls)).orElse(0.0));
        return new CostEstimate(modelTurns, judgeCalls, input, output, usd, basis);
    }

    /** Whether the estimate exceeds {@code limitUsd}; an estimate with no price always does. */
    public boolean exceeds(double limitUsd) {
        return usd.map(cost -> cost > limitUsd).orElse(true);
    }
}
