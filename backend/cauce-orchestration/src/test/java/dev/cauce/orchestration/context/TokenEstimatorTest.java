package dev.cauce.orchestration.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TokenEstimatorTest {

    @Test
    void estimateJson_estimatesTheCompactJsonRendering() {
        Map<String, Object> schema = Map.of("type", "object", "required", List.of("q"));
        // {"type":"object","required":["q"]} or the other key order: 35 chars => 10 tokens
        assertThat(TokenEstimator.estimateJson(schema)).isEqualTo(10);
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
        // 5 / 3.5 = 1.428..., ceil = 2
        assertThat(TokenEstimator.estimate("Hello")).isEqualTo(2);
    }

    @Test
    void estimate_whenSingleChar_roundsUpToOne() {
        // 1 / 3.5 = 0.285..., ceil = 1
        assertThat(TokenEstimator.estimate("x")).isEqualTo(1);
    }

    @Test
    void estimate_whenLengthIsExactMultiple_doesNotRoundUp() {
        // 7 / 3.5 = 2.0 exactly
        assertThat(TokenEstimator.estimate("x".repeat(7))).isEqualTo(2);
        // 350 / 3.5 = 100.0 exactly
        assertThat(TokenEstimator.estimate("x".repeat(350))).isEqualTo(100);
    }

    @Test
    void estimate_whenJustOverMultiple_roundsUp() {
        // 351 / 3.5 = 100.28..., ceil = 101
        assertThat(TokenEstimator.estimate("x".repeat(351))).isEqualTo(101);
    }
}
