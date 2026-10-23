package dev.cauce.orchestration.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.cauce.core.audit.AuditEvent;
import dev.cauce.core.audit.AuditEventRecorder;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.core.tenant.TenantNotFoundException;
import dev.cauce.orchestration.audit.UsageAuditEvents;
import dev.cauce.orchestration.persistence.LlmUsageRecordMapper;
import dev.cauce.orchestration.persistence.LlmUsageRecordRepository;
import dev.cauce.orchestration.usage.UsageReport.Granularity;
import dev.cauce.orchestration.usage.UsageReport.Grouping;
import dev.cauce.orchestration.usage.UsageReport.Row;
import dev.cauce.tenancy.TenantService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class LlmUsageQueryServiceTest {

    // 23:30 UTC on 31 October is already 1 November in Madrid.
    private static final Instant NOW = Instant.parse("2026-10-31T23:30:00Z");

    private final LlmUsageRecordRepository repository = Mockito.mock(LlmUsageRecordRepository.class);
    private final TenantService tenantService = Mockito.mock(TenantService.class);
    private final AuditEventRecorder auditRecorder = Mockito.mock(AuditEventRecorder.class);
    private final LlmUsageQueryService service = new LlmUsageQueryService(repository, new LlmUsageRecordMapper(),
            tenantService, auditRecorder, Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID partnerId = UUID.randomUUID();
    private final UUID clientId = UUID.randomUUID();
    private final UUID agentA = UUID.randomUUID();
    private final UUID agentB = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void usage_withoutDates_isTheCurrentMonthToToday_inTheZoneAsked() {
        TenantContext.setCurrentTenantId(clientId);

        UsageReport utc = service.usage(clientId, null, null, Granularity.DAY, Grouping.TENANT, "UTC");
        UsageReport madrid = service.usage(clientId, null, null, Granularity.DAY, Grouping.TENANT, "Europe/Madrid");

        assertThat(utc.from()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(utc.to()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(madrid.from()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(madrid.to()).isEqualTo(LocalDate.of(2026, 11, 1));
        verify(repository).aggregate(clientId, "day", "Europe/Madrid", LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 1));
    }

    @Test
    void usage_byTenant_sumsTheAgentsOfEachPeriod() {
        TenantContext.setCurrentTenantId(clientId);
        when(repository.aggregate(eq(clientId), eq("day"), eq("UTC"), any(), any())).thenReturn(List.of(
                row("2026-10-01", agentA, 100, 2, "0.10", 0),
                row("2026-10-01", agentB, 50, 1, null, 1),
                row("2026-10-02", agentA, 10, 1, "0.01", 0)));

        UsageReport report = service.usage(clientId, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2),
                Granularity.DAY, Grouping.TENANT, "UTC");

        assertThat(report.rows()).containsExactly(
                new Row("2026-10-01", clientId, null, 150, 0, 0, 15, 3, 3, new BigDecimal("0.10"), 1),
                new Row("2026-10-02", clientId, null, 10, 0, 0, 1, 1, 1, new BigDecimal("0.01"), 0));
    }

    @Test
    void usage_byAgent_keepsTheRows_andAMonthIsNamedByItsMonth() {
        TenantContext.setCurrentTenantId(clientId);
        when(repository.aggregate(eq(clientId), eq("month"), eq("UTC"), any(), any())).thenReturn(List.<Object[]>of(
                row("2026-10-01", agentA, 100, 2, "0.10", 0)));

        UsageReport report = service.usage(clientId, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                Granularity.MONTH, Grouping.AGENT, "UTC");

        assertThat(report.rows()).extracting(Row::period, Row::agentId).containsExactly(
                org.assertj.core.groups.Tuple.tuple("2026-10", agentA));
    }

    @Test
    void usage_readByAnotherTenant_isAudited_inTheChainOfTheTenantRead() {
        TenantContext.setCurrentTenantId(partnerId);

        service.usage(clientId, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), Granularity.DAY,
                Grouping.AGENT, "Europe/Madrid");

        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder).record(audit.capture());
        assertThat(audit.getValue().eventType()).isEqualTo(UsageAuditEvents.USAGE_READ);
        assertThat(audit.getValue().tenantId()).isEqualTo(clientId);
        assertThat(audit.getValue().payload()).containsEntry("actor_tenant_id", partnerId.toString())
                .containsEntry("from", "2026-10-01").containsEntry("time_zone", "Europe/Madrid")
                .containsEntry("group_by", "AGENT");
    }

    @Test
    void usage_readByTheTenantItself_isNotAudited() {
        TenantContext.setCurrentTenantId(clientId);

        service.usage(clientId, null, null, Granularity.DAY, Grouping.TENANT, "UTC");

        verifyNoInteractions(auditRecorder);
    }

    @Test
    void usage_ofATenantNotVisible_isNotFound_andReadsNothing() {
        TenantContext.setCurrentTenantId(partnerId);
        when(tenantService.getTenant(clientId)).thenThrow(new TenantNotFoundException("no"));

        assertThatThrownBy(() -> service.usage(clientId, null, null, Granularity.DAY, Grouping.TENANT, "UTC"))
                .isInstanceOf(TenantNotFoundException.class);
        verify(repository, never()).aggregate(any(), any(), any(), any(), any());
        verifyNoInteractions(auditRecorder);
    }

    @Test
    void usage_aReversedOrTooLongRange_orAnOffsetForAZone_isRefused() {
        TenantContext.setCurrentTenantId(clientId);

        assertThatThrownBy(() -> service.usage(clientId, LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1),
                Granularity.DAY, Grouping.TENANT, "UTC")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.usage(clientId, LocalDate.of(2025, 10, 1), LocalDate.of(2026, 10, 2),
                Granularity.DAY, Grouping.TENANT, "UTC")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("366");
        assertThatThrownBy(() -> service.usage(clientId, null, null, Granularity.DAY, Grouping.TENANT, "+02:00"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("+02:00");
        // 366 days, both ends included, is the longest range.
        service.usage(clientId, LocalDate.of(2025, 10, 2), LocalDate.of(2026, 10, 2), Granularity.MONTH,
                Grouping.TENANT, "UTC");
        verify(repository).aggregate(clientId, "month", "UTC", LocalDate.of(2025, 10, 2), LocalDate.of(2026, 10, 2));
    }

    /** A row as the repository returns it: one agent, {@code input} tokens, {@code input / 10} out. */
    private Object[] row(String day, UUID agentId, long input, long calls, String cost, long unpriced) {
        return new Object[] {day, clientId, agentId, input, 0L, 0L, input / 10, calls, calls,
                cost == null ? null : new BigDecimal(cost), unpriced};
    }
}
