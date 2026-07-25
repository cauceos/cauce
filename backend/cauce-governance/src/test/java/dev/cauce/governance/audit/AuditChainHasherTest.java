package dev.cauce.governance.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditChainHasherTest {

    private final AuditChainHasher hasher = new AuditChainHasher();

    private final UUID tenantId = UUID.randomUUID();
    private final UUID outboxId = UUID.randomUUID();
    private final Instant drainedAt = AuditLogEntry.mintDrainedAt();

    private String baselineEntryHash() {
        return hasher.entryHash(tenantId, 5, outboxId, "placeholder.event", drainedAt,
                "p".repeat(64), "q".repeat(64));
    }

    @Test
    void payloadHash_sameInput_isDeterministicHex64() {
        Map<String, Object> payload = Map.of("k", "v", "n", 1);

        String first = hasher.payloadHash(payload);

        assertThat(first).isEqualTo(hasher.payloadHash(payload)).hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void payloadHash_keyOrder_doesNotChangeTheHash() {
        Map<String, Object> ab = new LinkedHashMap<>();
        ab.put("a", 1);
        ab.put("b", 2);
        Map<String, Object> ba = new LinkedHashMap<>();
        ba.put("b", 2);
        ba.put("a", 1);

        assertThat(hasher.payloadHash(ab)).isEqualTo(hasher.payloadHash(ba));
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
        assertThat(baselineEntryHash()).isEqualTo(baselineEntryHash()).hasSize(64);
    }

    @Test
    void entryHash_changingAnyField_changesTheHash() {
        String baseline = baselineEntryHash();

        assertThat(hasher.entryHash(UUID.randomUUID(), 5, outboxId, "placeholder.event",
                drainedAt, "p".repeat(64), "q".repeat(64))).isNotEqualTo(baseline);
        assertThat(hasher.entryHash(tenantId, 6, outboxId, "placeholder.event", drainedAt,
                "p".repeat(64), "q".repeat(64))).isNotEqualTo(baseline);
        assertThat(hasher.entryHash(tenantId, 5, UUID.randomUUID(), "placeholder.event",
                drainedAt, "p".repeat(64), "q".repeat(64))).isNotEqualTo(baseline);
        assertThat(hasher.entryHash(tenantId, 5, outboxId, "other.event", drainedAt,
                "p".repeat(64), "q".repeat(64))).isNotEqualTo(baseline);
        assertThat(hasher.entryHash(tenantId, 5, outboxId, "placeholder.event",
                drainedAt.plusMillis(1), "p".repeat(64), "q".repeat(64))).isNotEqualTo(baseline);
        assertThat(hasher.entryHash(tenantId, 5, outboxId, "placeholder.event", drainedAt,
                "x".repeat(64), "q".repeat(64))).isNotEqualTo(baseline);
        assertThat(hasher.entryHash(tenantId, 5, outboxId, "placeholder.event", drainedAt,
                "p".repeat(64), "y".repeat(64))).isNotEqualTo(baseline);
    }
}
