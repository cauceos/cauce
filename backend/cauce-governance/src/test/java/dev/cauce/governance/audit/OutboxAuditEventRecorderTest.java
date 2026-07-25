package dev.cauce.governance.audit;

import dev.cauce.core.audit.AuditEvent;
import dev.cauce.core.audit.AuditEventRecorder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import dev.cauce.governance.persistence.AuditOutboxEntryEntity;
import dev.cauce.governance.persistence.AuditOutboxEntryMapper;
import dev.cauce.governance.persistence.AuditOutboxEntryRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class OutboxAuditEventRecorderTest {

    private final AuditOutboxEntryRepository repository =
            Mockito.mock(AuditOutboxEntryRepository.class);
    private final AuditOutboxEntryMapper mapper = new AuditOutboxEntryMapper();
    private final OutboxAuditEventRecorder recorder =
            new OutboxAuditEventRecorder(repository, mapper);

    @Test
    void record_persistsPendingEntryWithEventPayload() {
        UUID tenantId = UUID.randomUUID();
        AuditEvent event = new AuditEvent(tenantId, "placeholder.event", Map.of("k", "v"));

        recorder.record(event);

        verify(repository).save(Mockito.argThat((AuditOutboxEntryEntity entity) ->
                entity.getTenantId().equals(tenantId)
                        && entity.getEventType().equals("placeholder.event")
                        && entity.getPayload().equals(Map.of("k", "v"))
                        && entity.getDrainStatus() == DrainStatus.PENDING));
    }

    @Test
    void record_whenNullEvent_throwsNpe() {
        assertThatThrownBy(() -> recorder.record(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("event");
    }
}
