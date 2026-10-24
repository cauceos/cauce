package dev.cauce.orchestration.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TokenEstimatorTest {

    @Test
    void estimateJson_estimatesTheCompactJsonRendering() {
        Map<String, Object> schema = Map.of("type", "object", "required", List.of("q"));
        // {"type":"object","required":["q"]} or the other key order: 35 chars => 14 tokens
        assertThat(TokenEstimator.estimateJson(schema)).isEqualTo(14);
        assertThat(TokenEstimator.estimateJson(Map.of())).isEqualTo(1); // {}
    }

    @Test
    void estimate_whenEmpty_returnsZero() {
        assertThat(TokenEstimator.estimate("")).isZero();
    }

    @Test
    void estimate_whenNull_returnsZero() {
        assertThat(TokenEstimator.estimate(null)).isZero();
    }

    @Test
    void estimate_whenFiveChars_roundsUpToTwo() {
        // 5 / 2.5 = 2.0 exactly
        assertThat(TokenEstimator.estimate("Hello")).isEqualTo(2);
    }

    @Test
    void estimate_whenSingleChar_roundsUpToOne() {
        // 1 / 2.5 = 0.4, ceil = 1
        assertThat(TokenEstimator.estimate("x")).isEqualTo(1);
    }

    @Test
    void estimate_whenLengthIsExactMultiple_doesNotRoundUp() {
        // 5 / 2.5 = 2.0 exactly
        assertThat(TokenEstimator.estimate("x".repeat(5))).isEqualTo(2);
        // 250 / 2.5 = 100.0 exactly
        assertThat(TokenEstimator.estimate("x".repeat(250))).isEqualTo(100);
    }

    @Test
    void estimate_whenJustOverMultiple_roundsUp() {
        // 251 / 2.5 = 100.4, ceil = 101
        assertThat(TokenEstimator.estimate("x".repeat(251))).isEqualTo(101);
    }
}
