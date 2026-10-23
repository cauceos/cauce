package dev.cauce.orchestration.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.cauce.core.audit.AuditEvent;
import dev.cauce.core.audit.AuditEventRecorder;
import dev.cauce.core.tenant.InvalidTenantTierException;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.orchestration.audit.UsageAuditEvents;
import dev.cauce.orchestration.persistence.SpendLimitEntity;
import dev.cauce.orchestration.persistence.SpendLimitMapper;
import dev.cauce.orchestration.persistence.SpendLimitRepository;
import dev.cauce.orchestration.usage.SpendLimit.SpendState;
import dev.cauce.tenancy.TenantService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class SpendLimitServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-31T23:30:00Z");

    private final SpendLimitRepository repository = Mockito.mock(SpendLimitRepository.class);
    private final TenantService tenantService = Mockito.mock(TenantService.class);
    private final AuditEventRecorder auditRecorder = Mockito.mock(AuditEventRecorder.class);
    private final SpendLimitMapper mapper = new SpendLimitMapper();
    private final SpendLimitService service = new SpendLimitService(repository, mapper, tenantService,
            auditRecorder, Clock.fixed(NOW, ZoneOffset.UTC));

    private final Tenant operator = Tenant.operator("Operator");
    private final Tenant partner = Tenant.partner("Partner", operator.id());
    private final Tenant client = Tenant.client("Bar", partner.id());

    @BeforeEach
    void setUp() {
        when(tenantService.getTenant(client.id())).thenReturn(client);
        when(tenantService.getTenant(partner.id())).thenReturn(partner);
        when(repository.spentThisMonth(any())).thenReturn(List.<Object[]>of(new Object[] {new BigDecimal("42.5"), 3L}));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void setLimit_byThePartner_isSaved_audited_andReportsTheMonth() {
        TenantContext.setCurrentTenantId(partner.id());
        when(repository.findByTenantId(client.id())).thenReturn(Optional.empty());

        SpendStatus status = service.setLimit(client.id(), new BigDecimal("50"));

        ArgumentCaptor<SpendLimitEntity> saved = ArgumentCaptor.forClass(SpendLimitEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getMonthlyLimitUsd()).isEqualByComparingTo("50.00");
        assertThat(saved.getValue().getMonthlyLimitUsd().scale()).isEqualTo(2);
        assertThat(saved.getValue().getSetByTenantId()).isEqualTo(partner.id());
        assertThat(saved.getValue().getUpdatedAt()).isEqualTo(NOW);
        assertThat(status.period()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(status.spentUsd()).isEqualByComparingTo("42.5");
        assertThat(status.unpricedCalls()).isEqualTo(3);
        assertThat(status.state()).isEqualTo(SpendState.WARNING);
        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder).record(audit.capture());
        assertThat(audit.getValue().eventType()).isEqualTo(UsageAuditEvents.SPEND_LIMIT_CHANGED);
        assertThat(audit.getValue().tenantId()).isEqualTo(client.id());
        assertThat(audit.getValue().payload()).containsEntry("change", "set")
                .containsEntry("monthly_limit_usd", "50.00").containsEntry("actor_tenant_id", partner.id().toString());
    }

    @Test
    void setLimit_overAnExistingOne_keepsItsId_andAuditsThePrevious() {
        TenantContext.setCurrentTenantId(partner.id());
        SpendLimit existing = SpendLimit.create(client.id(), new BigDecimal("30.00"), operator.id(), NOW);
        when(repository.findByTenantId(client.id())).thenReturn(Optional.of(mapper.toEntity(existing)));

        service.setLimit(client.id(), new BigDecimal("80.00"));

        ArgumentCaptor<SpendLimitEntity> saved = ArgumentCaptor.forClass(SpendLimitEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(existing.id());
        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder).record(audit.capture());
        assertThat(audit.getValue().payload()).containsEntry("change", "changed")
                .containsEntry("previous_limit_usd", "30.00");
    }

    @Test
    void setLimit_byTheClientItself_isRefused() {
        TenantContext.setCurrentTenantId(client.id());

        assertThatThrownBy(() -> service.setLimit(client.id(), BigDecimal.TEN))
                .isInstanceOf(OwnSpendLimitException.class);
        assertThatThrownBy(() -> service.removeLimit(client.id())).isInstanceOf(OwnSpendLimitException.class);
        verify(repository, never()).save(any());
        verifyNoInteractions(auditRecorder);
    }

    @Test
    void setLimit_onAPartner_orWithMoreThanCents_isRefused() {
        TenantContext.setCurrentTenantId(operator.id());

        assertThatThrownBy(() -> service.setLimit(partner.id(), BigDecimal.TEN))
                .isInstanceOf(InvalidTenantTierException.class);
        assertThatThrownBy(() -> service.setLimit(client.id(), new BigDecimal("10.001")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("two decimals");
        assertThatThrownBy(() -> service.setLimit(client.id(), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void removeLimit_deletesAndAudits_orDoesNothingWithoutOne() {
        TenantContext.setCurrentTenantId(partner.id());
        SpendLimit existing = SpendLimit.create(client.id(), new BigDecimal("30.00"), partner.id(), NOW);
        SpendLimitEntity entity = mapper.toEntity(existing);
        when(repository.findByTenantId(client.id())).thenReturn(Optional.of(entity), Optional.empty());

        service.removeLimit(client.id());
        service.removeLimit(client.id());

        verify(repository).delete(entity);
        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder).record(audit.capture());
        assertThat(audit.getValue().payload()).containsEntry("change", "removed")
                .containsEntry("previous_limit_usd", "30.00");
    }

    @Test
    void status_withoutALimit_isNone_withTheMonthsSpend() {
        TenantContext.setCurrentTenantId(client.id());
        when(repository.findByTenantId(client.id())).thenReturn(Optional.empty());

        SpendStatus status = service.status(client.id());

        assertThat(status.state()).isEqualTo(SpendState.NONE);
        assertThat(status.limit()).isEmpty();
        assertThat(status.spentUsd()).isEqualByComparingTo("42.5");
    }
}
