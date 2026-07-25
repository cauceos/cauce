package dev.cauce.governance.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.core.audit.AuditEvent;
import dev.cauce.governance.audit.AuditOutboxEntry;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditOutboxEntryMapperTest {

    private final AuditOutboxEntryMapper mapper = new AuditOutboxEntryMapper();

    @Test
    void roundTrip_preservesAllFieldsIncludingJsonPayload() {
        AuditOutboxEntry original = AuditOutboxEntry.create(new AuditEvent(UUID.randomUUID(),
                "placeholder.event", Map.of("nested", Map.of("k", "v"), "n", 42)));

        AuditOutboxEntry roundTripped = mapper.toDomain(mapper.toEntity(original));

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void roundTrip_preservesDrainedStatus() {
        AuditOutboxEntry drained = AuditOutboxEntry.create(new AuditEvent(UUID.randomUUID(),
                "placeholder.event", Map.of())).drained();

        assertThat(mapper.toDomain(mapper.toEntity(drained))).isEqualTo(drained);
    }
}
