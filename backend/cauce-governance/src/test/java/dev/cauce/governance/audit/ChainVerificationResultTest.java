package dev.cauce.governance.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ChainVerificationResultTest {

    private static final ChainHead HEAD = new ChainHead(4, "h".repeat(64));

    @Test
    void valid_carriesCountsAndNoBreak() {
        ChainVerificationResult result = ChainVerificationResult.valid(4, 2);

        assertThat(result.valid()).isTrue();
        assertThat(result.verdict()).isEqualTo(ChainVerdict.VALID);
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
        assertThat(result.verdict()).isEqualTo(ChainVerdict.BROKEN);
        assertThat(result.brokenAtSequence()).isEqualTo(7);
        assertThat(result.breakKind()).isEqualTo(ChainBreakKind.PREV_HASH_MISMATCH);
    }

    @Test
    void constructor_nonBrokenWithBreak_throwsIllegalArgument() {
        for (ChainVerdict verdict : new ChainVerdict[] {ChainVerdict.VALID,
                ChainVerdict.UNVERIFIABLE}) {
            assertThatThrownBy(() -> new ChainVerificationResult(verdict, 1, 0, 1L,
                    ChainBreakKind.SEQUENCE_GAP, HEAD, null, SignatureReport.empty()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("only a broken result can carry a break");
        }
    }

    @Test
    void constructor_brokenWithoutBreak_throwsNpe() {
        assertThatThrownBy(() -> new ChainVerificationResult(ChainVerdict.BROKEN, 1, 0, null,
                null, HEAD, null, SignatureReport.empty()))
                .isInstanceOf(NullPointerException.class);
    }
}
