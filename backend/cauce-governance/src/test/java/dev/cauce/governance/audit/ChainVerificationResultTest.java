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
                    ChainBreakKind.SEQUENCE_GAP, HEAD, null, null, SignatureReport.empty()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("only a broken result can carry a break");
        }
    }

    @Test
    void constructor_brokenWithoutBreak_throwsNpe() {
        assertThatThrownBy(() -> new ChainVerificationResult(ChainVerdict.BROKEN, 1, 0, null,
                null, HEAD, null, null, SignatureReport.empty()))
                .isInstanceOf(NullPointerException.class);
    }

    /** TRUNCATED is only ever issued against an anchor; the type refuses to guess it. */
    @Test
    void constructor_truncatedWithoutAnchor_throwsIllegalArgument() {
        assertThatThrownBy(() -> new ChainVerificationResult(ChainVerdict.TRUNCATED, 1, 0, null,
                null, HEAD, null, null, SignatureReport.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("caller-supplied anchor");
    }

    @Test
    void constructor_truncatedWithAnchor_isAccepted() {
        ChainHead anchor = new ChainHead(9, "a".repeat(64));

        ChainVerificationResult result = new ChainVerificationResult(ChainVerdict.TRUNCATED, 4,
                0, null, null, HEAD, anchor, null, SignatureReport.empty());

        assertThat(result.valid()).isFalse();
        assertThat(result.expectedHead()).isEqualTo(anchor);
        assertThat(result.head()).isEqualTo(HEAD);
    }
}
