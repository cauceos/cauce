package dev.cauce.orchestration.context;

/**
 * How far the context budget's estimate of a call's input tokens was from what the provider
 * billed (ADR 0005, amendment of 2026-10-24). The budget only holds if the estimate tracks the
 * real count: an estimate far under it means windows overrun, far over it means history thrown
 * away for nothing. Pure, stateless utility.
 */
public final class InputTokenEstimate {

    /** The deviation beyond which a call is reported: 20 % of the billed input tokens. */
    public static final double MAX_DEVIATION = 0.20;

    private InputTokenEstimate() {
    }

    /**
     * The signed deviation of {@code estimated} from {@code actual}, as a fraction of
     * {@code actual}: negative when the estimate fell short. Zero when nothing was billed, since
     * there is no count to compare against.
     */
    public static double deviation(int estimated, int actual) {
        if (actual <= 0) {
            return 0.0;
        }
        return (double) (estimated - actual) / actual;
    }

    /** Whether {@code estimated} is more than {@link #MAX_DEVIATION} away from {@code actual}. */
    public static boolean exceeds(int estimated, int actual) {
        return Math.abs(deviation(estimated, actual)) > MAX_DEVIATION;
    }
}
