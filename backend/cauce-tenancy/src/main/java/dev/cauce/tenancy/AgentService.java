package dev.cauce.tenancy;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.agent.InvalidTenantTierException;
import dev.cauce.core.agent.TenantNotFoundException;
import dev.cauce.core.tenant.MissingTenantContextException;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.core.tenant.Tier;
import dev.cauce.memory.agent.AgentMapper;
import dev.cauce.memory.agent.AgentRepository;
import dev.cauce.memory.tenant.TenantEntity;
import dev.cauce.memory.tenant.TenantRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for managing agents. Every method is tenant-scoped:
 * {@code RlsContextAspect} establishes the RLS context from {@link TenantContext}
 * before each transactional method runs.
 */
@Service
public class AgentService {

    // TODO: replace hardcoded validation with the cauce-llm provider registry when the SPI is implemented.
    private static final Set<String> SUPPORTED_PROVIDERS = Set.of("openai", "anthropic", "mistral", "ollama");

    private final AgentRepository agentRepository;
    private final TenantRepository tenantRepository;
    private final AgentMapper agentMapper;

    public AgentService(AgentRepository agentRepository, TenantRepository tenantRepository,
                        AgentMapper agentMapper) {
        this.agentRepository = agentRepository;
        this.tenantRepository = tenantRepository;
        this.agentMapper = agentMapper;
    }

    /** Creates an agent with the default LLM configuration. */
    @Transactional
    public Agent createAgent(UUID tenantId, String name, String systemPrompt,
                             String modelProvider, String modelName) {
        return createAgent(tenantId, name, systemPrompt, modelProvider, modelName, null, null);
    }

    /**
     * Creates an agent, optionally overriding the LLM configuration. A null
     * {@code temperature} or {@code maxResponseTokens} falls back to the domain default;
     * non-null values are range-validated by the {@link Agent} factory.
     */
    @Transactional
    public Agent createAgent(UUID tenantId, String name, String systemPrompt,
                             String modelProvider, String modelName,
                             Double temperature, Integer maxResponseTokens) {
        TenantEntity tenant = tenantRepository.findById(tenantId).orElseThrow(() ->
                new TenantNotFoundException("No tenant found for id " + tenantId));
        if (tenant.getTier() != Tier.CLIENT) {
            throw new InvalidTenantTierException(
                    "Agents can only belong to a CLIENT tenant, but " + tenantId + " is " + tenant.getTier());
        }
        if (!SUPPORTED_PROVIDERS.contains(modelProvider)) {
            throw new IllegalArgumentException("Unsupported model provider: " + modelProvider);
        }

        Agent agent = Agent.create(tenantId, name, systemPrompt, modelProvider, modelName,
                temperature, maxResponseTokens);
        return agentMapper.toDomain(agentRepository.save(agentMapper.toEntity(agent)));
    }

    /** Returns the agent only if it belongs to the current context tenant (and RLS allows it). */
    @Transactional
    public Optional<Agent> getAgent(UUID agentId) {
        UUID currentTenant = TenantContext.getCurrentTenantId().orElseThrow(() ->
                new MissingTenantContextException("No tenant context set for getAgent"));
        return agentRepository.findByIdAndTenantId(agentId, currentTenant).map(agentMapper::toDomain);
    }

    /** Lists agents of the given tenant; RLS filters out tenants the context cannot see. */
    @Transactional
    public List<Agent> listAgentsForTenant(UUID tenantId) {
        return agentRepository.findByTenantId(tenantId).stream().map(agentMapper::toDomain).toList();
    }
}
