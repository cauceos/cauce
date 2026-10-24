package dev.cauce.orchestration.context;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.LongSupplier;

/**
 * Admits at most one input-token-estimate WARN per model and {@link #INTERVAL}: a constant that is
 * off is off on every call, and one line an hour says it as well as thousands. The calls left out
 * are counted and reported with the next admitted one. The metric records every call regardless.
 *
 * <p>The interval is a local monotonic duration (ADR 0008 allows these), measured with
 * {@link System#nanoTime()}: nothing here is stored or compared with a date.
 */
public final class InputTokenEstimateWarnings {

    /** How long a model's WARN silences the next ones. */
    public static final Duration INTERVAL = Duration.ofHours(1);

    private final LongSupplier nanoTime;
    private final Map<String, Window> windows = new HashMap<>();

    public InputTokenEstimateWarnings() {
        this(System::nanoTime);
    }

    InputTokenEstimateWarnings(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    /**
     * Whether a WARN for {@code provider}'s {@code model} may be logged now: if so, the number of
     * deviating calls left out since the previous one; if not, empty, and this call is counted.
     */
    public synchronized OptionalInt admit(String provider, String model) {
        String key = provider + "/" + model;
        long now = nanoTime.getAsLong();
        Window window = windows.get(key);
        if (window != null && now - window.admittedAt < INTERVAL.toNanos()) {
            window.suppressed++;
            return OptionalInt.empty();
        }
        int suppressed = window == null ? 0 : window.suppressed;
        windows.put(key, new Window(now));
        return OptionalInt.of(suppressed);
    }

    private static final class Window {
        final long admittedAt;
        int suppressed;

        Window(long admittedAt) {
            this.admittedAt = admittedAt;
        }
    }
}
