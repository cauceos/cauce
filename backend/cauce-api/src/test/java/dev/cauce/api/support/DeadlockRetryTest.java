package dev.cauce.api.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PessimisticLockingFailureException;

class DeadlockRetryTest {

    private static final Duration NO_PAUSE = Duration.ZERO;

    @Test
    void run_resetIsTheDeadlockVictim_runsItAgainUntilItSucceeds() {
        AtomicInteger runs = new AtomicInteger();

        DeadlockRetry.run(() -> {
            if (runs.incrementAndGet() < 3) {
                throw victim("40P01");
            }
        }, 5, NO_PAUSE);

        assertThat(runs).hasValue(3);
    }

    @Test
    void run_anyOtherFailure_isThrownAtOnce() {
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> DeadlockRetry.run(() -> {
            runs.incrementAndGet();
            throw victim("42P01");
        }, 5, NO_PAUSE)).isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(runs).hasValue(1);
    }

    @Test
    void run_stillADeadlockAfterTheLastAttempt_throwsIt() {
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> DeadlockRetry.run(() -> {
            runs.incrementAndGet();
            throw victim("40P01");
        }, 3, NO_PAUSE)).isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(runs).hasValue(3);
    }

    /** What Spring's JdbcTemplate throws for the 2026-11-10 failure: the SQLException two levels down. */
    private static RuntimeException victim(String sqlState) {
        return new PessimisticLockingFailureException("StatementCallback; SQL [TRUNCATE TABLE ...]",
                new SQLException("ERROR: deadlock detected", sqlState));
    }
}
