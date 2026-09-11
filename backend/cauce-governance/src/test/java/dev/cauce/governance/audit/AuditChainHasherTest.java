package dev.cauce.governance.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditChainHasherTest {

    private final AuditChainHasher hasher = new AuditChainHasher();

    private final UUID id = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID outboxId = UUID.randomUUID();
    private final Instant drainedAt = AuditLogEntry.mintDrainedAt();

    // Deliberately written as escapes: the two forms render identically, so spelling them
    // out is the only way a reader can see which is which. NFC is one code point (U+00E9),
    // NFD is "e" followed by the combining acute accent (U+0301).
    private static final String NFC_TEXT = "jos\u00e9";
    private static final String NFD_TEXT = "jose\u0301";

    private String baselineEntryHash(String scheme) {
        return hasher.entryHash(scheme, id, tenantId, 5, outboxId, "placeholder.event",
                drainedAt, "p".repeat(64), "q".repeat(64));
    }

    // === GOLDEN VECTORS: v1 is frozen history. These literals must never be edited. ===

    /**
     * Pins the v1 entry hash for fixed inputs, computed independently of this code. It is the
     * mechanical guarantee behind "existing entries are never re-hashed": any change to the v1
     * path — including one made accidentally while working on v2 — fails here rather than
     * silently invalidating every chain already written.
     */
    @Test
    void entryHash_v1_matchesTheFrozenGoldenVector() {
        UUID fixedTenant = UUID.fromString("00000000-0000-7000-8000-000000000001");
        UUID fixedOutbox = UUID.fromString("00000000-0000-7000-8000-0000000000a2");

        String hash = hasher.entryHash(AuditChainHasher.SCHEME_V1, UUID.randomUUID(), fixedTenant,
                5, fixedOutbox, "conduct.message.received", Instant.parse("2026-09-11T10:00:00Z"),
                "p".repeat(64), "q".repeat(64));

        assertThat(hash)
                .isEqualTo("417b2ecfa75612c53dca948f544f7309da10bf1031646485473d6e00a6054875");
    }

    /** The genesis hash is shared by both schemes and is equally frozen. */
    @Test
    void genesisHash_matchesTheFrozenGoldenVector() {
        UUID fixedTenant = UUID.fromString("00000000-0000-7000-8000-000000000001");

        assertThat(hasher.genesisHash(fixedTenant))
                .isEqualTo("0aaeefb6e95ffe2c9f1991f9964cbe1b7b4c200826d6d5c4149284a9be767fe5");
    }

    // === v2: the three corrections ===

    @Test
    void entryHash_v1AndV2_differForTheSameFields() {
        assertThat(baselineEntryHash(AuditChainHasher.SCHEME_V2))
                .isNotEqualTo(baselineEntryHash(AuditChainHasher.SCHEME_V1));
    }

    @Test
    void entryHash_v2_commitsToTheEntryId() {
        String other = hasher.entryHash(AuditChainHasher.SCHEME_V2, UUID.randomUUID(), tenantId, 5,
                outboxId, "placeholder.event", drainedAt, "p".repeat(64), "q".repeat(64));

        assertThat(other).isNotEqualTo(baselineEntryHash(AuditChainHasher.SCHEME_V2));
    }

    @Test
    void entryHash_v1_ignoresTheEntryId() {
        String other = hasher.entryHash(AuditChainHasher.SCHEME_V1, UUID.randomUUID(), tenantId, 5,
                outboxId, "placeholder.event", drainedAt, "p".repeat(64), "q".repeat(64));

        assertThat(other).isEqualTo(baselineEntryHash(AuditChainHasher.SCHEME_V1));
    }

    /**
     * A whole-second instant is the case v1 got wrong: {@code Instant.toString()} drops the
     * fraction entirely, so the serialised form could not be reproduced from the stored
     * timestamptz without guessing. v2 emits six digits either way.
     */
    @Test
    void entryHash_v2_wholeSecondAndSubSecondInstants_serializeAtFixedPrecision() {
        Instant wholeSecond = Instant.parse("2026-09-11T10:00:00Z");
        Instant oneMicroLater = wholeSecond.plusNanos(1_000);

        String atWholeSecond = hasher.entryHash(AuditChainHasher.SCHEME_V2, id, tenantId, 5,
                outboxId, "placeholder.event", wholeSecond, "p".repeat(64), "q".repeat(64));
        String atOneMicroLater = hasher.entryHash(AuditChainHasher.SCHEME_V2, id, tenantId, 5,
                outboxId, "placeholder.event", oneMicroLater, "p".repeat(64), "q".repeat(64));

        // Distinct instants stay distinct, and the whole second is reproducible: its preimage
        // is the six-digit form, which is what an external verifier would build.
        assertThat(atWholeSecond).isNotEqualTo(atOneMicroLater);
        assertThat(atWholeSecond).isEqualTo(hasher.entryHash(AuditChainHasher.SCHEME_V2, id,
                tenantId, 5, outboxId, "placeholder.event",
                Instant.parse("2026-09-11T10:00:00.000000Z"), "p".repeat(64), "q".repeat(64)));
    }

    @Test
    void entryHash_v2_normalizesEventTypeToNfc() {
        String nfc = hasher.entryHash(AuditChainHasher.SCHEME_V2, id, tenantId, 5, outboxId,
                NFC_TEXT, drainedAt, "p".repeat(64), "q".repeat(64));
        String nfd = hasher.entryHash(AuditChainHasher.SCHEME_V2, id, tenantId, 5, outboxId,
                NFD_TEXT, drainedAt, "p".repeat(64), "q".repeat(64));

        assertThat(nfd).isEqualTo(nfc);
    }

    @Test
    void entryHash_v1_doesNotNormalize() {
        String nfc = hasher.entryHash(AuditChainHasher.SCHEME_V1, id, tenantId, 5, outboxId,
                NFC_TEXT, drainedAt, "p".repeat(64), "q".repeat(64));
        String nfd = hasher.entryHash(AuditChainHasher.SCHEME_V1, id, tenantId, 5, outboxId,
                NFD_TEXT, drainedAt, "p".repeat(64), "q".repeat(64));

        assertThat(nfd).isNotEqualTo(nfc);
    }

    @Test
    void payloadHash_v2_normalizesValuesAndKeysToNfc() {
        assertThat(hasher.payloadHash(AuditChainHasher.SCHEME_V2, Map.of("k", NFD_TEXT)))
                .isEqualTo(hasher.payloadHash(AuditChainHasher.SCHEME_V2, Map.of("k", NFC_TEXT)));
        assertThat(hasher.payloadHash(AuditChainHasher.SCHEME_V2, Map.of(NFD_TEXT, "v")))
                .isEqualTo(hasher.payloadHash(AuditChainHasher.SCHEME_V2, Map.of(NFC_TEXT, "v")));
    }

    /** Why payloadHash takes a scheme at all: normalizing v1 payloads would break the chain. */
    @Test
    void payloadHash_v1_doesNotNormalize() {
        assertThat(hasher.payloadHash(AuditChainHasher.SCHEME_V1, Map.of("k", NFD_TEXT)))
                .isNotEqualTo(hasher.payloadHash(AuditChainHasher.SCHEME_V1, Map.of("k", NFC_TEXT)));
    }

    // === scheme handling ===

    @Test
    void supports_knownSchemesOnly() {
        assertThat(hasher.supports(AuditChainHasher.SCHEME_V1)).isTrue();
        assertThat(hasher.supports(AuditChainHasher.SCHEME_V2)).isTrue();
        assertThat(hasher.supports("v3")).isFalse();
        assertThat(hasher.supports(null)).isFalse();
    }

    @Test
    void currentScheme_isV2() {
        assertThat(AuditChainHasher.CURRENT_SCHEME).isEqualTo(AuditChainHasher.SCHEME_V2);
    }

    @Test
    void entryHash_unknownScheme_isRejectedRatherThanTreatedAsV1() {
        assertThatThrownBy(() -> hasher.entryHash("v3", id, tenantId, 5, outboxId,
                "placeholder.event", drainedAt, "p".repeat(64), "q".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown hash scheme");
    }

    // === shared behavior, asserted under both schemes ===

    @Test
    void payloadHash_sameInput_isDeterministicHex64() {
        Map<String, Object> payload = Map.of("k", "v", "n", 1);

        String first = hasher.payloadHash(AuditChainHasher.CURRENT_SCHEME, payload);

        assertThat(first).isEqualTo(hasher.payloadHash(AuditChainHasher.CURRENT_SCHEME, payload))
                .hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void payloadHash_keyOrder_doesNotChangeTheHash() {
        Map<String, Object> ab = new LinkedHashMap<>();
        ab.put("a", 1);
        ab.put("b", 2);
        Map<String, Object> ba = new LinkedHashMap<>();
        ba.put("b", 2);
        ba.put("a", 1);

        assertThat(hasher.payloadHash(AuditChainHasher.CURRENT_SCHEME, ab))
                .isEqualTo(hasher.payloadHash(AuditChainHasher.CURRENT_SCHEME, ba));
    }

    @Test
    void genesisHash_isStablePerTenantAndDifferentAcrossTenants() {
        UUID otherTenant = UUID.randomUUID();

        assertThat(hasher.genesisHash(tenantId))
                .isEqualTo(hasher.genesisHash(tenantId))
                .hasSize(64)
                .isNotEqualTo(hasher.genesisHash(otherTenant));
    }

    @Test
    void entryHash_sameFields_isDeterministic() {
        assertThat(baselineEntryHash(AuditChainHasher.SCHEME_V2))
                .isEqualTo(baselineEntryHash(AuditChainHasher.SCHEME_V2)).hasSize(64);
        assertThat(baselineEntryHash(AuditChainHasher.SCHEME_V1))
                .isEqualTo(baselineEntryHash(AuditChainHasher.SCHEME_V1)).hasSize(64);
    }

    @Test
    void entryHash_changingAnyField_changesTheHash() {
        for (String scheme : new String[] {AuditChainHasher.SCHEME_V1,
                AuditChainHasher.SCHEME_V2}) {
            String baseline = baselineEntryHash(scheme);

            assertThat(hasher.entryHash(scheme, id, UUID.randomUUID(), 5, outboxId,
                    "placeholder.event", drainedAt, "p".repeat(64), "q".repeat(64)))
                    .isNotEqualTo(baseline);
            assertThat(hasher.entryHash(scheme, id, tenantId, 6, outboxId, "placeholder.event",
                    drainedAt, "p".repeat(64), "q".repeat(64))).isNotEqualTo(baseline);
            assertThat(hasher.entryHash(scheme, id, tenantId, 5, UUID.randomUUID(),
                    "placeholder.event", drainedAt, "p".repeat(64), "q".repeat(64)))
                    .isNotEqualTo(baseline);
            assertThat(hasher.entryHash(scheme, id, tenantId, 5, outboxId, "other.event",
                    drainedAt, "p".repeat(64), "q".repeat(64))).isNotEqualTo(baseline);
            assertThat(hasher.entryHash(scheme, id, tenantId, 5, outboxId, "placeholder.event",
                    drainedAt.plusMillis(1), "p".repeat(64), "q".repeat(64)))
                    .isNotEqualTo(baseline);
            assertThat(hasher.entryHash(scheme, id, tenantId, 5, outboxId, "placeholder.event",
                    drainedAt, "x".repeat(64), "q".repeat(64))).isNotEqualTo(baseline);
            assertThat(hasher.entryHash(scheme, id, tenantId, 5, outboxId, "placeholder.event",
                    drainedAt, "p".repeat(64), "y".repeat(64))).isNotEqualTo(baseline);
        }
    }
}
