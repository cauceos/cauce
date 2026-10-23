package dev.cauce.tenancy;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.agent.AgentNotFoundException;
import dev.cauce.core.agent.UnknownToolException;
import dev.cauce.core.audit.AuditEventRecorder;
import dev.cauce.core.tenant.InvalidTenantTierException;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.core.tenant.TenantNotFoundException;
import dev.cauce.core.tenant.Tier;
import dev.cauce.core.tool.ToolCatalog;
import dev.cauce.memory.agent.AgentEntity;
import dev.cauce.memory.agent.AgentMapper;
import dev.cauce.memory.agent.AgentRepository;
import dev.cauce.memory.tenant.TenantEntity;
import dev.cauce.memory.tenant.TenantRepository;
import dev.cauce.tenancy.audit.AdminAuditEvents;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Limit;
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
    private final AuditEventRecorder auditRecorder;
    private final ObjectProvider<ToolCatalog> toolCatalog;

    /**
     * {@code toolCatalog} is optional: without a tool registry in the application (a module test
     * context), no tool exists, so granting any tool is rejected while an empty grant still works.
     */
    public AgentService(AgentRepository agentRepository, TenantRepository tenantRepository,
                        AgentMapper agentMapper, AuditEventRecorder auditRecorder,
                        ObjectProvider<ToolCatalog> toolCatalog) {
        this.agentRepository = agentRepository;
        this.tenantRepository = tenantRepository;
        this.agentMapper = agentMapper;
        this.auditRecorder = auditRecorder;
        this.toolCatalog = toolCatalog;
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
        return createAgent(tenantId, name, systemPrompt, modelProvider, modelName, temperature,
                maxResponseTokens, null, List.of());
    }

    /**
     * Creates an agent with its full configuration. {@code fallbackReply} is the text the
     * interlocutor receives when an invocation fails for good (ADR 0005, C3); {@code null} means
     * the platform default. {@code tools} are the tools it may use (ADR 0005, C4), each validated
     * against the tool catalog; an empty list — the default — grants none.
     *
     * @throws UnknownToolException if a tool name is not registered
     */
    @Transactional
    public Agent createAgent(UUID tenantId, String name, String systemPrompt,
                             String modelProvider, String modelName,
                             Double temperature, Integer maxResponseTokens, String fallbackReply,
                             List<String> tools) {
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
                temperature, maxResponseTokens).withFallbackReply(fallbackReply).withTools(tools);
        requireRegistered(agent.toolNames());
        Agent saved = agentMapper.toDomain(agentRepository.save(agentMapper.toEntity(agent)));
        // Admin audit in the same tx, in the owning tenant's chain; the prompt enters only
        // as a hash (sensitive, mutable business logic never lands in the append-only sink).
        auditRecorder.record(AdminAuditEvents.agentCreated(
                saved, TenantContext.getCurrentTenantId().orElseThrow()));
        return saved;
    }

    /**
     * Replaces the tools {@code agentId} may use with exactly {@code tools} (ADR 0005, C4). The
     * agent must be visible under the current context (RLS) and every name registered in the tool
     * catalog; an empty list revokes all. Recorded as {@code admin.agent.updated} in the agent's
     * tenant chain, with the granted names (non-sensitive metadata).
     *
     * @throws AgentNotFoundException if the agent is not visible
     * @throws UnknownToolException if a tool name is not registered
     */
    @Transactional
    public Agent setTools(UUID agentId, List<String> tools) {
        Agent current = agentRepository.findById(agentId).map(agentMapper::toDomain).orElseThrow(() ->
                new AgentNotFoundException("No agent found for id " + agentId));
        Agent retooled = current.retooled(tools);
        requireRegistered(retooled.toolNames());
        Agent saved = agentMapper.toDomain(agentRepository.save(agentMapper.toEntity(retooled)));
        auditRecorder.record(AdminAuditEvents.agentToolsUpdated(
                saved, TenantContext.getCurrentTenantId().orElseThrow()));
        return saved;
    }

    /**
     * Edits the agent's name, system prompt and fallback reply: each non-null argument replaces
     * the current value, a null one keeps it. The agent must be visible under the current context
     * (RLS). Recorded as {@code admin.agent.updated} in the agent's tenant chain, the prompt by
     * hash only.
     *
     * @throws AgentNotFoundException if the agent is not visible
     */
    @Transactional
    public Agent updateAgent(UUID agentId, String name, String systemPrompt, String fallbackReply) {
        return updateAgent(agentId, name, systemPrompt, fallbackReply, null);
    }

    /**
     * {@link #updateAgent(UUID, String, String, String)}, plus whether the agent elides past
     * lookups ({@code null} keeps it; deferred R1).
     */
    @Transactional
    public Agent updateAgent(UUID agentId, String name, String systemPrompt, String fallbackReply,
                             Boolean elidePastLookups) {
        Agent current = agentRepository.findById(agentId).map(agentMapper::toDomain).orElseThrow(() ->
                new AgentNotFoundException("No agent found for id " + agentId));
        Agent edited = current.edited(name, systemPrompt, fallbackReply);
        if (elidePastLookups != null) {
            edited = edited.withPastLookupsElided(elidePastLookups);
        }
        Agent saved = agentMapper.toDomain(agentRepository.save(agentMapper.toEntity(edited)));
        auditRecorder.record(AdminAuditEvents.agentConfigurationUpdated(
                saved, TenantContext.getCurrentTenantId().orElseThrow()));
        return saved;
    }

    private void requireRegistered(List<String> toolNames) {
        if (toolNames.isEmpty()) {
            return;
        }
        ToolCatalog catalog = toolCatalog.getIfAvailable();
        List<String> unknown = toolNames.stream()
                .filter(name -> catalog == null || !catalog.contains(name))
                .toList();
        if (!unknown.isEmpty()) {
            throw new UnknownToolException("No registered tool named " + String.join(", ", unknown));
        }
    }

    /**
     * Returns the agent if it is visible under the current tenant context. Visibility is
     * hierarchical: a tenant sees its own agents and those of its descendants. RLS does the
     * filtering (the aspect sets the context); not-found and not-visible are intentionally
     * indistinguishable. Aligned with {@code getTenant}/{@code listAgentsForTenant} — see ADR 0002.
     */
    @Transactional
    public Optional<Agent> getAgent(UUID agentId) {
        return agentRepository.findById(agentId).map(agentMapper::toDomain);
    }

    /**
     * Lists up to {@code maxRows} agents of the given tenant, ordered by id (UUIDv7, i.e.
     * creation order), starting after {@code afterId} when non-null (keyset pagination).
     * RLS filters out tenants the context cannot see.
     */
    @Transactional
    public List<Agent> listAgentsForTenant(UUID tenantId, UUID afterId, int maxRows) {
        List<AgentEntity> page = afterId == null
                ? agentRepository.findByTenantIdOrderByIdAsc(tenantId, Limit.of(maxRows))
                : agentRepository.findByTenantIdAndIdGreaterThanOrderByIdAsc(
                        tenantId, afterId, Limit.of(maxRows));
        return page.stream().map(agentMapper::toDomain).toList();
    }
}
