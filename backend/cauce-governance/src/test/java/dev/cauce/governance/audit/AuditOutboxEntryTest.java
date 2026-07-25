package dev.cauce.governance.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditOutboxEntryTest {

    private final UUID tenantId = UUID.randomUUID();

    @Test
    void create_fromEvent_mintsUuidV7PendingEntry() {
        Instant before = Instant.now();
        AuditEvent event = new AuditEvent(tenantId, "placeholder.event", Map.of("k", "v"));

        AuditOutboxEntry entry = AuditOutboxEntry.create(event);

        assertThat(entry.id()).isNotNull();
        assertThat(entry.id().version()).isEqualTo(7);
        assertThat(entry.tenantId()).isEqualTo(tenantId);
        assertThat(entry.eventType()).isEqualTo("placeholder.event");
        assertThat(entry.payload()).containsExactlyEntriesOf(Map.of("k", "v"));
        assertThat(entry.drainStatus()).isEqualTo(DrainStatus.PENDING);
        assertThat(entry.createdAt()).isBetween(before, Instant.now());
    }

    @Test
    void create_whenNullEvent_throwsNpe() {
        assertThatThrownBy(() -> AuditOutboxEntry.create(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("event");
    }

    @Test
    void drained_returnsDrainedCopyPreservingEverythingElse() {
        AuditOutboxEntry entry = AuditOutboxEntry.create(
                new AuditEvent(tenantId, "placeholder.event", Map.of("k", "v")));

        AuditOutboxEntry drained = entry.drained();

        assertThat(drained.drainStatus()).isEqualTo(DrainStatus.DRAINED);
        assertThat(drained.id()).isEqualTo(entry.id());
        assertThat(drained.tenantId()).isEqualTo(entry.tenantId());
        assertThat(drained.eventType()).isEqualTo(entry.eventType());
        assertThat(drained.payload()).isEqualTo(entry.payload());
        assertThat(drained.createdAt()).isEqualTo(entry.createdAt());
    }

    @Test
    void constructor_whenBlankEventType_throwsIllegalArgument() {
        assertThatThrownBy(() -> new AuditOutboxEntry(UUID.randomUUID(), tenantId, "",
                Map.of(), DrainStatus.PENDING, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");
    }
}
