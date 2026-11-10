package dev.cauce.api.support;

import java.sql.SQLException;
import java.time.Duration;

/**
 * Retries a test's database reset when Postgres aborts it as a deadlock victim.
 *
 * <p>The API ITs share one Postgres, and Spring keeps every distinct test context alive with its own
 * async worker polling {@code pending_invocations} across all tenants. The {@code TRUNCATE} of one
 * class's setup can therefore cross the locks of another context's worker, still claiming or
 * processing an invocation left by an earlier test. Postgres breaks the cycle by aborting one side
 * ({@code 40P01}); when that is the reset, running it again succeeds, because the worker's
 * transaction has gone on. Only a deadlock is retried: any other failure is a real one.
 */
public final class DeadlockRetry {

    /** Postgres's SQLSTATE for "deadlock detected". */
    static final String DEADLOCK = "40P01";

    private DeadlockRetry() {
    }

    /** Runs {@code reset}, again after {@code pause} each time it is a deadlock victim, at most {@code attempts} times. */
    public static void run(Runnable reset, int attempts, Duration pause) {
        for (int attempt = 1; ; attempt++) {
            try {
                reset.run();
                return;
            } catch (RuntimeException e) {
                if (attempt >= attempts || !isDeadlock(e)) {
                    throw e;
                }
                sleep(pause);
            }
        }
    }

    static boolean isDeadlock(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && DEADLOCK.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static void sleep(Duration pause) {
        try {
            Thread.sleep(pause.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while retrying a deadlocked reset", e);
        }
    }
}
