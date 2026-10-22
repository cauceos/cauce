package dev.cauce.llm.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LlmUsageTest {

    @Test
    void of_countsTheCachedPartsWithinTheInput_andZeroWithoutCaching() {
        LlmUsage cached = LlmUsage.of(4000, 100, 3500, 300);
        LlmUsage plain = LlmUsage.of(4000, 100);

        assertThat(cached.totalTokens()).isEqualTo(4100);
        assertThat(cached.cacheReadTokens()).isEqualTo(3500);
        assertThat(cached.cacheWriteTokens()).isEqualTo(300);
        assertThat(plain.cacheReadTokens()).isZero();
        assertThat(plain.cacheWriteTokens()).isZero();
    }

    @Test
    void cachedTokens_cannotBeNegative_norExceedTheInput() {
        assertThatThrownBy(() -> LlmUsage.of(100, 1, -1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LlmUsage.of(100, 1, 80, 30)).isInstanceOf(IllegalArgumentException.class);
    }
}
