package dev.cauce.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.cauce.business.catalog.CatalogEntity;
import dev.cauce.business.catalog.CatalogMapper;
import dev.cauce.business.catalog.CatalogRepository;
import dev.cauce.business.catalog.CatalogService;
import dev.cauce.business.profile.BusinessProfileEntity;
import dev.cauce.business.profile.BusinessProfileMapper;
import dev.cauce.business.profile.BusinessProfileRepository;
import dev.cauce.business.profile.BusinessProfileService;
import dev.cauce.core.audit.AuditEvent;
import dev.cauce.core.audit.AuditEventRecorder;
import dev.cauce.core.business.catalog.CatalogItem;
import dev.cauce.core.business.profile.BusinessProfile;
import dev.cauce.core.business.profile.BusinessProfile.Fact;
import dev.cauce.core.tenant.InvalidTenantTierException;
import dev.cauce.core.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BusinessKnowledgeServicesTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final BusinessTenants businessTenants = mock(BusinessTenants.class);
    private final AuditEventRecorder auditRecorder = mock(AuditEventRecorder.class);

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenantId(actorId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void putProfile_createsTheFirstProfile_andAuditsItAsTheActor() {
        BusinessProfileRepository repository = mock(BusinessProfileRepository.class);
        when(repository.findByTenantId(tenantId)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        BusinessProfileService service = new BusinessProfileService(repository, new BusinessProfileMapper(),
                businessTenants, auditRecorder);

        BusinessProfile saved = service.putProfile(tenantId, "Bar", "Europe/Madrid", null,
                List.of(new Fact("terraza", "sí")));

        assertThat(saved.tenantId()).isEqualTo(tenantId);
        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder).record(audit.capture());
        assertThat(audit.getValue().eventType()).isEqualTo("admin.business_profile.updated");
        assertThat(audit.getValue().payload()).containsEntry("actor_tenant_id", actorId.toString())
                .containsEntry("fact_count", 1).doesNotContainValue("sí");
    }

    @Test
    void putProfile_replacesAnExistingProfile_keepingItsId() {
        BusinessProfileRepository repository = mock(BusinessProfileRepository.class);
        BusinessProfile existing = BusinessProfile.create(tenantId, "Bar", "UTC", "es", List.of());
        BusinessProfileEntity entity = new BusinessProfileMapper().toEntity(existing);
        when(repository.findByTenantId(tenantId)).thenReturn(Optional.of(entity));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        BusinessProfileService service = new BusinessProfileService(repository, new BusinessProfileMapper(),
                businessTenants, auditRecorder);

        assertThat(service.putProfile(tenantId, "Bar 2", "UTC", "es", List.of()).id()).isEqualTo(existing.id());
    }

    @Test
    void putCatalog_forANonBusiness_savesAndAuditsNothing() {
        CatalogRepository repository = mock(CatalogRepository.class);
        doThrow(new InvalidTenantTierException("not a business")).when(businessTenants).requireBusiness(tenantId);
        CatalogService service = new CatalogService(repository, new CatalogMapper(), businessTenants, auditRecorder);

        assertThatThrownBy(() -> service.putCatalog(tenantId, List.of(),
                List.of(new CatalogItem("a", "S", "A", null, null, Map.of()))))
                .isInstanceOf(InvalidTenantTierException.class);
        verify(repository, never()).save(any(CatalogEntity.class));
        verify(auditRecorder, never()).record(any());
    }
}
