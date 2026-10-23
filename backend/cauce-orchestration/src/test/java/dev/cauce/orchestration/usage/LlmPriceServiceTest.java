package dev.cauce.orchestration.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.cauce.core.audit.AuditEvent;
import dev.cauce.core.audit.AuditEventRecorder;
import dev.cauce.core.tenant.InvalidTenantTierException;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.orchestration.audit.UsageAuditEvents;
import dev.cauce.orchestration.persistence.LlmPriceEntity;
import dev.cauce.orchestration.persistence.LlmPriceMapper;
import dev.cauce.orchestration.persistence.LlmPriceRepository;
import dev.cauce.tenancy.TenantService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class LlmPriceServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    private final LlmPriceRepository repository = Mockito.mock(LlmPriceRepository.class);
    private final TenantService tenantService = Mockito.mock(TenantService.class);
    private final AuditEventRecorder auditRecorder = Mockito.mock(AuditEventRecorder.class);
    private final LlmPriceService service = new LlmPriceService(repository, new LlmPriceMapper(), tenantService,
            auditRecorder, Clock.fixed(NOW, ZoneOffset.UTC));

    private final Tenant operator = Tenant.operator("Operator");
    private final Tenant partner = Tenant.partner("Partner", operator.id());

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void addPrice_byTheOperator_isInForceFromTheClock_andAudited() {
        TenantContext.setCurrentTenantId(operator.id());
        when(tenantService.getTenant(operator.id())).thenReturn(operator);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        LlmPrice price = service.addPrice("anthropic", "claude-sonnet-5-5", new BigDecimal("2.00"),
                new BigDecimal("10.00"), new BigDecimal("0.10"), new BigDecimal("2.50"), new BigDecimal("4.00"));

        assertThat(price.tenantId()).isEqualTo(operator.id());
        assertThat(price.validFrom()).isEqualTo(NOW);
        verify(repository).save(any(LlmPriceEntity.class));
        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder).record(audit.capture());
        assertThat(audit.getValue().eventType()).isEqualTo(UsageAuditEvents.LLM_PRICE_ADDED);
        assertThat(audit.getValue().tenantId()).isEqualTo(operator.id());
        assertThat(audit.getValue().payload()).containsEntry("model", "claude-sonnet-5-5")
                .containsEntry("input_usd_per_mtok", "2.00")
                .containsEntry("cache_write_long_usd_per_mtok", "4.00")
                .containsEntry("actor_tenant_id", operator.id().toString());
    }

    @Test
    void addPrice_byAPartner_isRefused() {
        TenantContext.setCurrentTenantId(partner.id());
        when(tenantService.getTenant(partner.id())).thenReturn(partner);

        assertThatThrownBy(() -> service.addPrice("anthropic", "m", BigDecimal.ONE, BigDecimal.ONE, null, null, null))
                .isInstanceOf(InvalidTenantTierException.class);
        verifyNoInteractions(repository, auditRecorder);
    }

    @Test
    void addPrice_unsupportedProvider_isRefused() {
        TenantContext.setCurrentTenantId(operator.id());
        when(tenantService.getTenant(operator.id())).thenReturn(operator);

        assertThatThrownBy(() -> service.addPrice("acme", "m", BigDecimal.ONE, BigDecimal.ONE, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("acme");
        verifyNoInteractions(repository, auditRecorder);
    }
}
