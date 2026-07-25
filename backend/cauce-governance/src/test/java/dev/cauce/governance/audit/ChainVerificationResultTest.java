package dev.cauce.governance.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ChainVerificationResultTest {

    @Test
    void valid_carriesCountsAndNoBreak() {
        ChainVerificationResult result = ChainVerificationResult.valid(4, 2);

        assertThat(result.valid()).isTrue();
        assertThat(result.chainedCount()).isEqualTo(4);
        assertThat(result.preChainCount()).isEqualTo(2);
        assertThat(result.brokenAtSequence()).isNull();
        assertThat(result.breakKind()).isNull();
    }

    @Test
    void broken_requiresSequenceAndKind() {
        ChainVerificationResult result = ChainVerificationResult.broken(
                7, ChainBreakKind.PREV_HASH_MISMATCH, 6, 0);

        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSequence()).isEqualTo(7);
        assertThat(result.breakKind()).isEqualTo(ChainBreakKind.PREV_HASH_MISMATCH);
    }

    @Test
    void constructor_validWithBreak_throwsIllegalArgument() {
        assertThatThrownBy(() -> new ChainVerificationResult(true, 1, 0, 1L,
                ChainBreakKind.SEQUENCE_GAP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid result cannot carry a break");
    }

    @Test
    void constructor_invalidWithoutBreak_throwsNpe() {
        assertThatThrownBy(() -> new ChainVerificationResult(false, 1, 0, null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
