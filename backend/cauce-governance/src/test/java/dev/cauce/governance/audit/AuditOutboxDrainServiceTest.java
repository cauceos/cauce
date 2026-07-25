package dev.cauce.governance.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.cauce.governance.persistence.AuditLogEntryEntity;
import dev.cauce.governance.persistence.AuditLogEntryMapper;
import dev.cauce.governance.persistence.AuditLogEntryRepository;
import dev.cauce.governance.persistence.AuditOutboxEntryEntity;
import dev.cauce.governance.persistence.AuditOutboxEntryMapper;
import dev.cauce.governance.persistence.AuditOutboxEntryRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.Limit;

class AuditOutboxDrainServiceTest {

    private final AuditOutboxEntryRepository outboxRepository =
            Mockito.mock(AuditOutboxEntryRepository.class);
    private final AuditLogEntryRepository logRepository =
            Mockito.mock(AuditLogEntryRepository.class);
    private final AuditOutboxEntryMapper outboxMapper = new AuditOutboxEntryMapper();
    private final AuditLogEntryMapper logMapper = new AuditLogEntryMapper();
    private final AuditOutboxDrainService service = new AuditOutboxDrainService(
            outboxRepository, outboxMapper, logRepository, logMapper);

    private final UUID tenantId = UUID.randomUUID();

    private AuditOutboxEntryEntity pendingEntity(String eventType) {
        return outboxMapper.toEntity(AuditOutboxEntry.create(
                new AuditEvent(tenantId, eventType, Map.of("k", "v"))));
    }

    @Test
    void drainBatch_withPendingRows_assignsContiguousSequenceFromMaxPlusOne() {
        List<AuditOutboxEntryEntity> pending =
                List.of(pendingEntity("e.one"), pendingEntity("e.two"), pendingEntity("e.three"));
        when(outboxRepository.findByTenantIdAndDrainStatusOrderByCreatedAtAscIdAsc(
                eq(tenantId), eq(DrainStatus.PENDING), any(Limit.class))).thenReturn(pending);
        when(logRepository.findMaxSequenceNumber(tenantId)).thenReturn(Optional.of(5L));

        int drained = service.drainBatch(tenantId, 10);

        assertThat(drained).isEqualTo(3);
        ArgumentCaptor<AuditLogEntryEntity> logCaptor =
                ArgumentCaptor.forClass(AuditLogEntryEntity.class);
        verify(logRepository, times(3)).save(logCaptor.capture());
        assertThat(logCaptor.getAllValues()).extracting(AuditLogEntryEntity::getSequenceNumber)
                .containsExactly(6L, 7L, 8L);
        assertThat(logCaptor.getAllValues()).extracting(AuditLogEntryEntity::getOutboxId)
                .containsExactlyElementsOf(pending.stream()
                        .map(AuditOutboxEntryEntity::getId).toList());

        ArgumentCaptor<AuditOutboxEntryEntity> outboxCaptor =
                ArgumentCaptor.forClass(AuditOutboxEntryEntity.class);
        verify(outboxRepository, times(3)).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getAllValues())
                .allSatisfy(entity ->
                        assertThat(entity.getDrainStatus()).isEqualTo(DrainStatus.DRAINED));
    }

    @Test
    void drainBatch_firstDrainOfTenant_startsSequenceAtOne() {
        when(outboxRepository.findByTenantIdAndDrainStatusOrderByCreatedAtAscIdAsc(
                eq(tenantId), eq(DrainStatus.PENDING), any(Limit.class)))
                .thenReturn(List.of(pendingEntity("e.one")));
        when(logRepository.findMaxSequenceNumber(tenantId)).thenReturn(Optional.empty());

        service.drainBatch(tenantId, 10);

        ArgumentCaptor<AuditLogEntryEntity> captor =
                ArgumentCaptor.forClass(AuditLogEntryEntity.class);
        verify(logRepository).save(captor.capture());
        assertThat(captor.getValue().getSequenceNumber()).isEqualTo(1L);
    }

    @Test
    void drainBatch_noPendingRows_returnsZeroWithoutTouchingTheLedger() {
        when(outboxRepository.findByTenantIdAndDrainStatusOrderByCreatedAtAscIdAsc(
                eq(tenantId), eq(DrainStatus.PENDING), any(Limit.class))).thenReturn(List.of());

        int drained = service.drainBatch(tenantId, 10);

        assertThat(drained).isZero();
        verify(logRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void drainBatch_passesBatchSizeAsQueryLimit() {
        when(outboxRepository.findByTenantIdAndDrainStatusOrderByCreatedAtAscIdAsc(
                eq(tenantId), eq(DrainStatus.PENDING), any(Limit.class))).thenReturn(List.of());

        service.drainBatch(tenantId, 42);

        ArgumentCaptor<Limit> captor = ArgumentCaptor.forClass(Limit.class);
        verify(outboxRepository).findByTenantIdAndDrainStatusOrderByCreatedAtAscIdAsc(
                eq(tenantId), eq(DrainStatus.PENDING), captor.capture());
        assertThat(captor.getValue().max()).isEqualTo(42);
    }

    @Test
    void pendingTenants_delegatesToTheEscapeHatchQuery() {
        List<UUID> tenants = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(outboxRepository.pendingTenants()).thenReturn(tenants);

        assertThat(service.pendingTenants()).isEqualTo(tenants);
    }
}
