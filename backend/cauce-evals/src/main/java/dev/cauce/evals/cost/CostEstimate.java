package dev.cauce.evals.cost;

import java.util.Optional;

/**
 * What a run is expected to cost before it starts (ADR 0009, E10): the model turns it will drive
 * times the tokens a turn took in the previous run (or {@link #DEFAULT_INPUT_PER_TURN} and
 * {@link #DEFAULT_OUTPUT_PER_TURN} the first time), plus the judge's calls when its price is known.
 * The input is priced with the cache share measured in the last run of the same model
 * ({@link CacheShare}); without one, uncached, a ceiling. Priced uncached, the full battery of
 * 2026-11-09 was estimated at 9.96 US$ against a 10 US$ limit and cost 1.65.
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
     * The shares of a run's input read from the cache, written to it, and written to it for a long
     * retention (a part of the writes), as measured in a run.
     */
    public record CacheShare(double read, double write, double writeLong) {

        public CacheShare {
            if (read < 0 || write < 0 || writeLong < 0 || read + write > 1 || writeLong > write) {
                throw new IllegalArgumentException("not a cache share: read " + read + ", write " + write
                        + ", long write " + writeLong);
            }
        }

        /** The share of a run that used {@code input} tokens, empty when it used none. */
        public static Optional<CacheShare> of(long input, long cacheRead, long cacheWrite, long cacheWriteLong) {
            if (input <= 0) {
                return Optional.empty();
            }
            return Optional.of(new CacheShare((double) cacheRead / input, (double) cacheWrite / input,
                    (double) cacheWriteLong / input));
        }
    }

    /** {@link #of(int, int, Long, Long, Optional, Optional, Optional)}, priced uncached: the ceiling. */
    public static CostEstimate of(int modelTurns, int judgeCalls, Long inputPerTurn, Long outputPerTurn,
                                  Optional<PriceTable.Price> agentPrice, Optional<PriceTable.Price> judgePrice) {
        return of(modelTurns, judgeCalls, inputPerTurn, outputPerTurn, Optional.empty(), agentPrice, judgePrice);
    }

    /**
     * @param inputPerTurn and {@code outputPerTurn} from the previous run, or null for the defaults
     * @param cacheShare the cache share measured in the last run of the same model; empty prices the
     *                   input uncached
     */
    public static CostEstimate of(int modelTurns, int judgeCalls, Long inputPerTurn, Long outputPerTurn,
                                  Optional<CacheShare> cacheShare, Optional<PriceTable.Price> agentPrice,
                                  Optional<PriceTable.Price> judgePrice) {
        long in = inputPerTurn == null ? DEFAULT_INPUT_PER_TURN : inputPerTurn;
        long out = outputPerTurn == null ? DEFAULT_OUTPUT_PER_TURN : outputPerTurn;
        String perTurn = inputPerTurn == null ? "valores por defecto por turno (" + in + " de entrada, " + out
                + " de salida)" : "tokens por turno de la ejecución anterior (" + in + " / " + out + ")";
        String basis = perTurn + cacheShare.map(share -> String.format(java.util.Locale.ROOT,
                ", %.1f %% de la entrada leída de caché y %.1f %% escrita, como en la última del modelo",
                share.read() * 100, share.write() * 100)).orElse(", sin caché: un techo");
        long input = in * modelTurns;
        long output = out * modelTurns;
        long read = cacheShare.map(share -> Math.round(input * share.read())).orElse(0L);
        long write = cacheShare.map(share -> Math.round(input * share.write())).orElse(0L);
        long writeLong = cacheShare.map(share -> Math.min(write, Math.round(input * share.writeLong()))).orElse(0L);
        Optional<Double> usd = agentPrice.map(price -> price.cost(input, read, write, writeLong, output)
                + judgePrice.map(j -> j.cost(JUDGE_INPUT * judgeCalls, 0, 0, JUDGE_OUTPUT * judgeCalls)).orElse(0.0));
        return new CostEstimate(modelTurns, judgeCalls, input, output, usd, basis);
    }

    /** Whether the estimate exceeds {@code limitUsd}; an estimate with no price always does. */
    public boolean exceeds(double limitUsd) {
        return usd.map(cost -> cost > limitUsd).orElse(true);
    }
}
