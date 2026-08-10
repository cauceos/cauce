package dev.cauce.api.audit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.governance.audit.ChainBreakKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Unit tests for the internal {@link ChainBreakKind} -> public {@link BreakClassification}
 * mapping: every internal kind maps to exactly the expected public value, and the mapping is
 * total (the {@code @EnumSource} sweep would fail on any unmapped kind, mirroring the
 * exhaustive switch that would break the build if a new kind were added).
 */
class BreakClassificationTest {

    @Test
    void from_payloadHashMismatch_mapsToEntryAltered() {
        assertThat(BreakClassification.from(ChainBreakKind.PAYLOAD_HASH_MISMATCH))
                .isEqualTo(BreakClassification.ENTRY_ALTERED);
    }

    @Test
    void from_entryHashMismatch_mapsToEntryAltered() {
        assertThat(BreakClassification.from(ChainBreakKind.ENTRY_HASH_MISMATCH))
                .isEqualTo(BreakClassification.ENTRY_ALTERED);
    }

    @Test
    void from_prevHashMismatch_mapsToLinkBroken() {
        assertThat(BreakClassification.from(ChainBreakKind.PREV_HASH_MISMATCH))
                .isEqualTo(BreakClassification.LINK_BROKEN);
    }

    @Test
    void from_sequenceGap_mapsToEntryMissing() {
        assertThat(BreakClassification.from(ChainBreakKind.SEQUENCE_GAP))
                .isEqualTo(BreakClassification.ENTRY_MISSING);
    }

    @Test
    void from_preChainAfterChained_mapsToUnchainedEntryOutOfOrder() {
        assertThat(BreakClassification.from(ChainBreakKind.PRE_CHAIN_AFTER_CHAINED))
                .isEqualTo(BreakClassification.UNCHAINED_ENTRY_OUT_OF_ORDER);
    }

    @Test
    void from_malformedEntry_mapsToEntryMalformed() {
        assertThat(BreakClassification.from(ChainBreakKind.MALFORMED_ENTRY))
                .isEqualTo(BreakClassification.ENTRY_MALFORMED);
    }

    @ParameterizedTest
    @EnumSource(ChainBreakKind.class)
    void from_everyInternalKind_mapsToSomePublicValue(ChainBreakKind kind) {
        assertThat(BreakClassification.from(kind)).isNotNull();
    }
}
