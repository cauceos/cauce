package dev.cauce.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.cauce.core.tenant.InvalidTenantTierException;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantNotFoundException;
import dev.cauce.core.tenant.Tier;
import dev.cauce.memory.tenant.TenantEntity;
import dev.cauce.memory.tenant.TenantMapper;
import dev.cauce.memory.tenant.TenantRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TenantServiceTest {

    private TenantRepository repository;
    private TenantService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(TenantRepository.class);
        // bootstrapOperator is not exercised by these unit tests; a bare mock suffices.
        service = new TenantService(repository, new TenantMapper(),
                Mockito.mock(OperatorBootstrap.class));
        when(repository.save(any(TenantEntity.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void createPartner_whenOperatorNotFound_throws() {
        UUID operatorId = UUID.randomUUID();
        when(repository.findById(operatorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createPartner("P", operatorId))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void createPartner_whenParentIsClient_throws() {
        UUID clientId = UUID.randomUUID();
        when(repository.findById(clientId)).thenReturn(Optional.of(entity(clientId, Tier.CLIENT)));

        assertThatThrownBy(() -> service.createPartner("P", clientId))
                .isInstanceOf(InvalidTenantTierException.class);
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
    void createClient_whenParentIsOperator_throws() {
        UUID operatorId = UUID.randomUUID();
        when(repository.findById(operatorId)).thenReturn(Optional.of(entity(operatorId, Tier.OPERATOR)));

        assertThatThrownBy(() -> service.createClient("C", operatorId))
                .isInstanceOf(InvalidTenantTierException.class);
    }

    @Test
    void createClient_whenPartnerValid_persistsAndReturnsClient() {
        UUID partnerId = UUID.randomUUID();
        when(repository.findById(partnerId)).thenReturn(Optional.of(entity(partnerId, Tier.PARTNER)));

        Tenant client = service.createClient("Client Co", partnerId);

        assertThat(client.tier()).isEqualTo(Tier.CLIENT);
        assertThat(client.parentTenantId()).isEqualTo(partnerId);
    }

    private static TenantEntity entity(UUID id, Tier tier) {
        Instant now = Instant.now();
        return new TenantEntity(id, null, tier, "name", now, now);
    }
}
