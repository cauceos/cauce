package dev.cauce.governance.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.governance.audit.AuditEvent;
import dev.cauce.governance.audit.AuditLogEntry;
import dev.cauce.governance.audit.AuditOutboxEntry;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditLogEntryMapperTest {

    private final AuditLogEntryMapper mapper = new AuditLogEntryMapper();

    @Test
    void roundTrip_preservesAllFieldsWithNullChainColumns() {
        AuditLogEntry original = AuditLogEntry.fromOutbox(AuditOutboxEntry.create(
                new AuditEvent(UUID.randomUUID(), "placeholder.event", Map.of("k", "v"))), 3);

        AuditLogEntry roundTripped = mapper.toDomain(mapper.toEntity(original));

        assertThat(roundTripped).isEqualTo(original);
        assertThat(roundTripped.prevHash()).isNull();
        assertThat(roundTripped.entryHash()).isNull();
        assertThat(roundTripped.signature()).isNull();
    }

    @Test
    void roundTrip_preservesFilledChainColumns() {
        // The chain unit will fill these; the mapper must already carry them faithfully.
        AuditLogEntry withChain = new AuditLogEntry(UUID.randomUUID(), UUID.randomUUID(), 9,
                UUID.randomUUID(), "placeholder.event", Map.of(), Instant.now(),
                "prev-hash-hex", "entry-hash-hex", "signature-bytes");

        assertThat(mapper.toDomain(mapper.toEntity(withChain))).isEqualTo(withChain);
    }
}
