package dev.cauce.governance.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditLogEntryTest {

    private final UUID tenantId = UUID.randomUUID();

    private AuditOutboxEntry outboxEntry() {
        return AuditOutboxEntry.create(
                new AuditEvent(tenantId, "placeholder.event", Map.of("k", "v")));
    }

    @Test
    void fromOutbox_numbersTheEntryAndLeavesChainColumnsNull() {
        AuditOutboxEntry outbox = outboxEntry();
        Instant before = Instant.now();

        AuditLogEntry entry = AuditLogEntry.fromOutbox(outbox, 7);

        assertThat(entry.id()).isNotNull();
        assertThat(entry.id().version()).isEqualTo(7);
        assertThat(entry.tenantId()).isEqualTo(tenantId);
        assertThat(entry.sequenceNumber()).isEqualTo(7);
        assertThat(entry.outboxId()).isEqualTo(outbox.id());
        assertThat(entry.eventType()).isEqualTo("placeholder.event");
        assertThat(entry.payload()).isEqualTo(outbox.payload());
        assertThat(entry.drainedAt()).isBetween(before, Instant.now());
        // Reserved for the hash-chain unit: this unit never fills them.
        assertThat(entry.prevHash()).isNull();
        assertThat(entry.entryHash()).isNull();
        assertThat(entry.signature()).isNull();
    }

    @Test
    void fromOutbox_whenNullOutboxEntry_throwsNpe() {
        assertThatThrownBy(() -> AuditLogEntry.fromOutbox(null, 1))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("outboxEntry");
    }

    @Test
    void constructor_whenSequenceBelowOne_throwsIllegalArgument() {
        AuditOutboxEntry outbox = outboxEntry();

        assertThatThrownBy(() -> AuditLogEntry.fromOutbox(outbox, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequenceNumber");
    }
}
