package dev.cauce.orchestration.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class InputTokenEstimateTest {

    @Test
    void deviation_isSignedAndRelativeToTheBilledCount() {
        assertThat(InputTokenEstimate.deviation(1200, 1000)).isCloseTo(0.20, within(1e-9));
        assertThat(InputTokenEstimate.deviation(750, 1000)).isCloseTo(-0.25, within(1e-9));
    }

    @Test
    void exceeds_onlyBeyondTwentyPercentEitherWay() {
        assertThat(InputTokenEstimate.exceeds(1200, 1000)).isFalse();
        assertThat(InputTokenEstimate.exceeds(1201, 1000)).isTrue();
        assertThat(InputTokenEstimate.exceeds(800, 1000)).isFalse();
        assertThat(InputTokenEstimate.exceeds(799, 1000)).isTrue();
    }

    @Test
    void deviation_whenNothingWasBilled_isZero() {
        assertThat(InputTokenEstimate.deviation(500, 0)).isZero();
        assertThat(InputTokenEstimate.exceeds(500, 0)).isFalse();
    }
}
