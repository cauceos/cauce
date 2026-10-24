package dev.cauce.orchestration.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class InputTokenEstimateWarningsTest {

    private final AtomicLong nanos = new AtomicLong(1_000);
    private final InputTokenEstimateWarnings warnings = new InputTokenEstimateWarnings(nanos::get);

    @Test
    void admit_oncePerModelAndHour_countingWhatItLeftOut() {
        assertThat(warnings.admit("anthropic", "claude-sonnet-5-5")).isEqualTo(OptionalInt.of(0));
        assertThat(warnings.admit("anthropic", "claude-sonnet-5-5")).isEmpty();
        advance(Duration.ofMinutes(59));
        assertThat(warnings.admit("anthropic", "claude-sonnet-5-5")).isEmpty();

        advance(Duration.ofMinutes(1));
        assertThat(warnings.admit("anthropic", "claude-sonnet-5-5")).isEqualTo(OptionalInt.of(2));
        assertThat(warnings.admit("anthropic", "claude-sonnet-5-5")).isEmpty();
    }

    @Test
    void admit_eachModelHasItsOwnHour() {
        assertThat(warnings.admit("anthropic", "claude-sonnet-5-5")).isPresent();
        assertThat(warnings.admit("anthropic", "claude-haiku-5-5")).isPresent();
        assertThat(warnings.admit("ollama", "claude-sonnet-5-5")).isPresent();
        assertThat(warnings.admit("anthropic", "claude-haiku-5-5")).isEmpty();
    }

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }
}
