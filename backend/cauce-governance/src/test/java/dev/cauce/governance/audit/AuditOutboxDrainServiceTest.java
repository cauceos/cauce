package dev.cauce.governance.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.cauce.governance.persistence.AuditChainHeadEntity;
import dev.cauce.governance.persistence.AuditChainHeadMapper;
import dev.cauce.governance.persistence.AuditChainHeadRepository;
import dev.cauce.governance.persistence.AuditLogEntryEntity;
import dev.cauce.governance.persistence.AuditLogEntryMapper;
import dev.cauce.governance.persistence.AuditLogEntryRepository;
import dev.cauce.governance.persistence.AuditOutboxEntryEntity;
import dev.cauce.governance.persistence.AuditOutboxEntryMapper;
import dev.cauce.governance.persistence.AuditOutboxEntryRepository;
import java.time.Instant;
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
    private final AuditChainHeadRepository headRepository =
            Mockito.mock(AuditChainHeadRepository.class);
    private final AuditOutboxEntryMapper outboxMapper = new AuditOutboxEntryMapper();
    private final AuditLogEntryMapper logMapper = new AuditLogEntryMapper();
    private final AuditChainHeadMapper headMapper = new AuditChainHeadMapper();
    private final AuditChainHasher hasher = new AuditChainHasher();
    private final AuditOutboxDrainService service = new AuditOutboxDrainService(
            outboxRepository, outboxMapper, logRepository, logMapper, headRepository,
            headMapper, hasher);

    private final UUID tenantId = UUID.randomUUID();

    private AuditOutboxEntryEntity pendingEntity(String eventType) {
        return outboxMapper.toEntity(AuditOutboxEntry.create(
                new AuditEvent(tenantId, eventType, Map.of("k", "v"))));
    }

    private void stubPending(AuditOutboxEntryEntity... entities) {
        when(outboxRepository.findByTenantIdAndDrainStatusOrderByCreatedAtAscIdAsc(
                eq(tenantId), eq(DrainStatus.PENDING), any(Limit.class)))
                .thenReturn(List.of(entities));
    }

    private List<AuditLogEntryEntity> drainAndCaptureEntries(int expectedCount, int batchSize) {
        int drained = service.drainBatch(tenantId, batchSize);
        assertThat(drained).isEqualTo(expectedCount);
        ArgumentCaptor<AuditLogEntryEntity> captor =
                ArgumentCaptor.forClass(AuditLogEntryEntity.class);
        verify(logRepository, times(expectedCount)).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void drainBatch_withExistingHead_takesSequenceAndPrevHashFromThatSingleRow() {
        stubPending(pendingEntity("e.one"), pendingEntity("e.two"));
        AuditChainHeadEntity head = headMapper.toEntity(
                AuditChainHead.of(tenantId, 5, "h".repeat(64)));
        when(headRepository.findByTenantId(tenantId)).thenReturn(Optional.of(head));

        List<AuditLogEntryEntity> entries = drainAndCaptureEntries(2, 10);

        // Both the next sequence AND the prev_hash come from the same head row.
        assertThat(entries).extracting(AuditLogEntryEntity::getSequenceNumber)
                .containsExactly(6L, 7L);
        assertThat(entries.get(0).getPrevHash()).isEqualTo("h".repeat(64));
        // Within the batch the chain links entry to entry.
        assertThat(entries.get(1).getPrevHash()).isEqualTo(entries.get(0).getEntryHash());
        // The head advanced to the last appended entry, in the same logical unit.
        assertThat(head.getLastSequenceNumber()).isEqualTo(7L);
        assertThat(head.getLastEntryHash()).isEqualTo(entries.get(1).getEntryHash());
        verify(logRepository, never()).findMaxSequenceNumber(any()); // MAX is init-only

        ArgumentCaptor<AuditOutboxEntryEntity> outboxCaptor =
                ArgumentCaptor.forClass(AuditOutboxEntryEntity.class);
        verify(outboxRepository, times(2)).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getAllValues())
                .allSatisfy(entity ->
                        assertThat(entity.getDrainStatus()).isEqualTo(DrainStatus.DRAINED));
    }

    @Test
    void drainBatch_withoutHead_initializesFromMaxSequenceAndGenesisAndInsertsHead() {
        stubPending(pendingEntity("e.one"));
        when(headRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());
        when(logRepository.findMaxSequenceNumber(tenantId)).thenReturn(Optional.of(2L));

        List<AuditLogEntryEntity> entries = drainAndCaptureEntries(1, 10);

        // Continues past pre-chain rows; the chain itself starts at the tenant's genesis.
        assertThat(entries.get(0).getSequenceNumber()).isEqualTo(3L);
        assertThat(entries.get(0).getPrevHash()).isEqualTo(hasher.genesisHash(tenantId));
        ArgumentCaptor<AuditChainHeadEntity> headCaptor =
                ArgumentCaptor.forClass(AuditChainHeadEntity.class);
        verify(headRepository).save(headCaptor.capture());
        assertThat(headCaptor.getValue().getTenantId()).isEqualTo(tenantId);
        assertThat(headCaptor.getValue().getLastSequenceNumber()).isEqualTo(3L);
        assertThat(headCaptor.getValue().getLastEntryHash())
                .isEqualTo(entries.get(0).getEntryHash());
    }

    @Test
    void drainBatch_firstDrainOfFreshTenant_startsSequenceAtOneFromGenesis() {
        stubPending(pendingEntity("e.one"));
        when(headRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());
        when(logRepository.findMaxSequenceNumber(tenantId)).thenReturn(Optional.empty());

        List<AuditLogEntryEntity> entries = drainAndCaptureEntries(1, 10);

        assertThat(entries.get(0).getSequenceNumber()).isEqualTo(1L);
        assertThat(entries.get(0).getPrevHash()).isEqualTo(hasher.genesisHash(tenantId));
    }

    @Test
    void drainBatch_stampsVerifiableHashesOnEveryEntry() {
        stubPending(pendingEntity("e.one"));
        when(headRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());
        when(logRepository.findMaxSequenceNumber(tenantId)).thenReturn(Optional.empty());

        AuditLogEntryEntity entry = drainAndCaptureEntries(1, 10).get(0);

        assertThat(entry.getHashScheme()).isEqualTo(AuditChainHasher.SCHEME);
        assertThat(entry.getPayloadHash()).isEqualTo(hasher.payloadHash(entry.getPayload()));
        assertThat(entry.getEntryHash()).isEqualTo(hasher.entryHash(tenantId,
                entry.getSequenceNumber(), entry.getOutboxId(), entry.getEventType(),
                entry.getDrainedAt(), entry.getPayloadHash(), entry.getPrevHash()));
        assertThat(entry.getSignature()).isNull(); // the signing slot stays untouched
        // What was hashed is exactly what will be stored: microsecond precision.
        assertThat(entry.getDrainedAt())
                .isEqualTo(entry.getDrainedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    @Test
    void drainBatch_noPendingRows_returnsZeroWithoutTouchingLedgerOrHead() {
        when(outboxRepository.findByTenantIdAndDrainStatusOrderByCreatedAtAscIdAsc(
                eq(tenantId), eq(DrainStatus.PENDING), any(Limit.class))).thenReturn(List.of());

        int drained = service.drainBatch(tenantId, 10);

        assertThat(drained).isZero();
        verify(logRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
        verify(headRepository, never()).findByTenantId(any());
        verify(headRepository, never()).save(any());
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

    @Test
    void drainBatch_updatedAtOfExistingHead_advances() {
        stubPending(pendingEntity("e.one"));
        AuditChainHeadEntity head = new AuditChainHeadEntity(tenantId, 1, "h".repeat(64),
                Instant.parse("2020-01-01T00:00:00Z"));
        when(headRepository.findByTenantId(tenantId)).thenReturn(Optional.of(head));

        service.drainBatch(tenantId, 10);

        assertThat(head.getUpdatedAt()).isAfter(Instant.parse("2020-01-01T00:00:00Z"));
    }
}
