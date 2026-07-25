package dev.cauce.tenancy;

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
import dev.cauce.core.tenant.TenantNotFoundException;
import dev.cauce.core.tenant.Tier;
import dev.cauce.memory.tenant.TenantEntity;
import dev.cauce.memory.tenant.TenantMapper;
import dev.cauce.memory.tenant.TenantRepository;
import dev.cauce.tenancy.audit.AdminAuditEvents;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class TenantServiceTest {

    private final UUID actorTenantId = UUID.randomUUID();

    private TenantRepository repository;
    private AuditEventRecorder auditRecorder;
    private TenantService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(TenantRepository.class);
        auditRecorder = Mockito.mock(AuditEventRecorder.class);
        // bootstrapOperator is not exercised by these unit tests; a bare mock suffices.
        service = new TenantService(repository, new TenantMapper(),
                Mockito.mock(OperatorBootstrap.class), auditRecorder);
        when(repository.save(any(TenantEntity.class))).thenAnswer(call -> call.getArgument(0));
        // In production RlsContextAspect guarantees a context before these methods run; the
        // audit event captures it as the actor.
        TenantContext.setCurrentTenantId(actorTenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createPartner_whenOperatorNotFound_throws() {
        UUID operatorId = UUID.randomUUID();
        when(repository.findById(operatorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createPartner("P", operatorId))
                .isInstanceOf(TenantNotFoundException.class);
        verifyNoInteractions(auditRecorder); // no fact, no audit record
    }

    @Test
    void createPartner_whenParentIsClient_throws() {
        UUID clientId = UUID.randomUUID();
        when(repository.findById(clientId)).thenReturn(Optional.of(entity(clientId, Tier.CLIENT)));

        assertThatThrownBy(() -> service.createPartner("P", clientId))
                .isInstanceOf(InvalidTenantTierException.class);
        verifyNoInteractions(auditRecorder);
    }

    @Test
    void createPartner_whenOperatorValid_persistsAndReturnsPartner() {
        UUID operatorId = UUID.randomUUID();
        when(repository.findById(operatorId)).thenReturn(Optional.of(entity(operatorId, Tier.OPERATOR)));

        Tenant partner = service.createPartner("Partner Co", operatorId);

        assertThat(partner.tier()).isEqualTo(Tier.PARTNER);
        assertThat(partner.parentTenantId()).isEqualTo(operatorId);
        assertThat(partner.name()).isEqualTo("Partner Co");
    }

    @Test
    void createPartner_recordsAdminAuditInTheNewTenantsChainWithActor() {
        UUID operatorId = UUID.randomUUID();
        when(repository.findById(operatorId)).thenReturn(Optional.of(entity(operatorId, Tier.OPERATOR)));

        Tenant partner = service.createPartner("Partner Co", operatorId);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder).record(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.tenantId()).isEqualTo(partner.id()); // the SUBJECT's chain
        assertThat(event.eventType()).isEqualTo(AdminAuditEvents.TENANT_CREATED);
        assertThat(event.payload())
                .containsEntry("tenant_id", partner.id().toString())
                .containsEntry("tier", "PARTNER")
                .containsEntry("parent_tenant_id", operatorId.toString())
                .containsEntry("actor_tenant_id", actorTenantId.toString());
        // The business name stays in the mutable tenants row, never in the sink.
        assertThat(event.payload().values()).doesNotContain("Partner Co");
    }

    @Test
    void createClient_whenParentIsOperator_throws() {
        UUID operatorId = UUID.randomUUID();
        when(repository.findById(operatorId)).thenReturn(Optional.of(entity(operatorId, Tier.OPERATOR)));

        assertThatThrownBy(() -> service.createClient("C", operatorId))
                .isInstanceOf(InvalidTenantTierException.class);
        verifyNoInteractions(auditRecorder);
    }

    @Test
    void createClient_whenPartnerValid_persistsAuditsAndReturnsClient() {
        UUID partnerId = UUID.randomUUID();
        when(repository.findById(partnerId)).thenReturn(Optional.of(entity(partnerId, Tier.PARTNER)));

        Tenant client = service.createClient("Client Co", partnerId);

        assertThat(client.tier()).isEqualTo(Tier.CLIENT);
        assertThat(client.parentTenantId()).isEqualTo(partnerId);
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder).record(captor.capture());
        assertThat(captor.getValue().tenantId()).isEqualTo(client.id());
        assertThat(captor.getValue().payload()).containsEntry("tier", "CLIENT");
    }

    private static TenantEntity entity(UUID id, Tier tier) {
        Instant now = Instant.now();
        return new TenantEntity(id, null, tier, "name", now, now);
    }
}
