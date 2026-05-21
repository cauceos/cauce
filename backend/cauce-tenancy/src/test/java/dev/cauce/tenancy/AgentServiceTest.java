package dev.cauce.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.agent.AgentStatus;
import dev.cauce.core.agent.InvalidTenantTierException;
import dev.cauce.core.agent.TenantNotFoundException;
import dev.cauce.core.tenant.Tier;
import dev.cauce.memory.agent.AgentEntity;
import dev.cauce.memory.agent.AgentMapper;
import dev.cauce.memory.agent.AgentRepository;
import dev.cauce.memory.tenant.TenantEntity;
import dev.cauce.memory.tenant.TenantRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AgentServiceTest {

    private AgentRepository agentRepository;
    private TenantRepository tenantRepository;
    private AgentService service;

    @BeforeEach
    void setUp() {
        agentRepository = Mockito.mock(AgentRepository.class);
        tenantRepository = Mockito.mock(TenantRepository.class);
        service = new AgentService(agentRepository, tenantRepository, new AgentMapper());
        when(agentRepository.save(any(AgentEntity.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void createAgent_whenTenantNotFound_throwsTenantNotFound() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createAgent(tenantId, "Bot", "p", "anthropic", "m"))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void createAgent_whenTenantIsOperator_throwsInvalidTenantTier() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant(tenantId, Tier.OPERATOR)));

        assertThatThrownBy(() -> service.createAgent(tenantId, "Bot", "p", "anthropic", "m"))
                .isInstanceOf(InvalidTenantTierException.class);
    }

    @Test
    void createAgent_whenTenantIsPartner_throwsInvalidTenantTier() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant(tenantId, Tier.PARTNER)));

        assertThatThrownBy(() -> service.createAgent(tenantId, "Bot", "p", "anthropic", "m"))
                .isInstanceOf(InvalidTenantTierException.class);
    }

    @Test
    void createAgent_whenProviderUnsupported_throws() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant(tenantId, Tier.CLIENT)));

        assertThatThrownBy(() -> service.createAgent(tenantId, "Bot", "p", "no-such-provider", "m"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createAgent_whenClientTenantValid_persistsAndReturnsAgent() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant(tenantId, Tier.CLIENT)));

        Agent agent = service.createAgent(tenantId, "DentalBot", "You are helpful.",
                "anthropic", "claude-sonnet-4-7");

        assertThat(agent.tenantId()).isEqualTo(tenantId);
        assertThat(agent.name()).isEqualTo("DentalBot");
        assertThat(agent.modelProvider()).isEqualTo("anthropic");
        assertThat(agent.status()).isEqualTo(AgentStatus.DRAFT);
    }

    private static TenantEntity tenant(UUID id, Tier tier) {
        Instant now = Instant.now();
        return new TenantEntity(id, null, tier, "name", now, now);
    }
}
