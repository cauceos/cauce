package dev.cauce.governance.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.core.audit.AuditEvent;
import dev.cauce.governance.audit.AuditLogEntry;
import dev.cauce.governance.audit.AuditOutboxEntry;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditLogEntryMapperTest {

    private final AuditLogEntryMapper mapper = new AuditLogEntryMapper();

    @Test
    void roundTrip_preservesAllFieldsIncludingChainColumns() {
        AuditLogEntry original = AuditLogEntry.chained(AuditOutboxEntry.create(
                        new AuditEvent(UUID.randomUUID(), "placeholder.event", Map.of("k", "v"))),
                3, AuditLogEntry.mintDrainedAt(), "payload-hash-hex", "prev-hash-hex",
                "entry-hash-hex", "v1");

        AuditLogEntry roundTripped = mapper.toDomain(mapper.toEntity(original));

        assertThat(roundTripped).isEqualTo(original);
        assertThat(roundTripped.payloadHash()).isEqualTo("payload-hash-hex");
        assertThat(roundTripped.prevHash()).isEqualTo("prev-hash-hex");
        assertThat(roundTripped.entryHash()).isEqualTo("entry-hash-hex");
        assertThat(roundTripped.hashScheme()).isEqualTo("v1");
        assertThat(roundTripped.signature()).isNull();
    }

    @Test
    void roundTrip_preservesAPreChainRowWithNullChainColumnsAndNullPayload() {
        // Rows drained before the chain unit (and owner-redacted payloads) must map
        // faithfully so the verifier can judge them instead of the mapper rejecting them.
        AuditLogEntry preChain = new AuditLogEntry(UUID.randomUUID(), UUID.randomUUID(), 9,
                UUID.randomUUID(), "placeholder.event", null, Instant.now(), null, null, null,
                null, null);

        AuditLogEntry roundTripped = mapper.toDomain(mapper.toEntity(preChain));

        assertThat(roundTripped).isEqualTo(preChain);
        assertThat(roundTripped.payload()).isNull();
        assertThat(roundTripped.entryHash()).isNull();
    }
}
