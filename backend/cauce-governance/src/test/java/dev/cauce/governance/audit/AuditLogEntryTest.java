package dev.cauce.governance.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditLogEntryTest {

    private static final String HEX_64 = "a".repeat(64);

    private final UUID tenantId = UUID.randomUUID();

    private AuditOutboxEntry outboxEntry() {
        return AuditOutboxEntry.create(
                new AuditEvent(tenantId, "placeholder.event", Map.of("k", "v")));
    }

    @Test
    void chained_stampsChainFieldsAndLeavesSignatureNull() {
        AuditOutboxEntry outbox = outboxEntry();
        Instant drainedAt = AuditLogEntry.mintDrainedAt();

        AuditLogEntry entry = AuditLogEntry.chained(outbox, 7, drainedAt, HEX_64,
                "b".repeat(64), "c".repeat(64), AuditChainHasher.SCHEME);

        assertThat(entry.id()).isNotNull();
        assertThat(entry.id().version()).isEqualTo(7);
        assertThat(entry.tenantId()).isEqualTo(tenantId);
        assertThat(entry.sequenceNumber()).isEqualTo(7);
        assertThat(entry.outboxId()).isEqualTo(outbox.id());
        assertThat(entry.eventType()).isEqualTo("placeholder.event");
        assertThat(entry.payload()).isEqualTo(outbox.payload());
        assertThat(entry.drainedAt()).isEqualTo(drainedAt);
        assertThat(entry.payloadHash()).isEqualTo(HEX_64);
        assertThat(entry.prevHash()).isEqualTo("b".repeat(64));
        assertThat(entry.entryHash()).isEqualTo("c".repeat(64));
        assertThat(entry.hashScheme()).isEqualTo("v1");
        // Reserved for the signing unit: never filled here.
        assertThat(entry.signature()).isNull();
    }

    @Test
    void mintDrainedAt_isTruncatedToMicroseconds() {
        Instant minted = AuditLogEntry.mintDrainedAt();

        // What is hashed must equal what PostgreSQL stores and reads back (microseconds).
        assertThat(minted).isEqualTo(minted.truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void chained_whenNullOutboxEntry_throwsNpe() {
        assertThatThrownBy(() -> AuditLogEntry.chained(null, 1, Instant.now(), HEX_64, HEX_64,
                HEX_64, AuditChainHasher.SCHEME))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("outboxEntry");
    }

    @Test
    void chained_whenNullHash_throwsNpe() {
        assertThatThrownBy(() -> AuditLogEntry.chained(outboxEntry(), 1, Instant.now(), HEX_64,
                null, HEX_64, AuditChainHasher.SCHEME))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("prevHash");
    }

    @Test
    void constructor_whenSequenceBelowOne_throwsIllegalArgument() {
        assertThatThrownBy(() -> AuditLogEntry.chained(outboxEntry(), 0, Instant.now(), HEX_64,
                HEX_64, HEX_64, AuditChainHasher.SCHEME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequenceNumber");
    }

    @Test
    void constructor_allowsNullPayload_representingAnOwnerRedactedRow() {
        AuditLogEntry redacted = new AuditLogEntry(UUID.randomUUID(), tenantId, 3,
                UUID.randomUUID(), "placeholder.event", null, Instant.now(), HEX_64, HEX_64,
                HEX_64, AuditChainHasher.SCHEME, null);

        assertThat(redacted.payload()).isNull();
        assertThat(redacted.payloadHash()).isEqualTo(HEX_64); // verifiability survives
    }
}
