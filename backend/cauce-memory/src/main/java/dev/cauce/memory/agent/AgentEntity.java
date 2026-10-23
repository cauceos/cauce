package dev.cauce.memory.agent;

import dev.cauce.core.agent.AgentStatus;
import dev.cauce.core.agent.PromptCacheRetention;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA persistence mapping for an agent row. Infrastructure detail of cauce-memory;
 * the domain type is {@link dev.cauce.core.agent.Agent}, converted by {@link AgentMapper}.
 */
@Entity
@Table(name = "agents")
public class AgentEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "system_prompt", nullable = false)
    private String systemPrompt;

    @Column(name = "model_provider", nullable = false, length = 20)
    private String modelProvider;

    @Column(name = "model_name", nullable = false, length = 100)
    private String modelName;

    // Nullable in the DB to preserve pre-V7 rows; the domain coalesces null to its default.
    @Column(name = "temperature")
    private Double temperature;

    @Column(name = "max_response_tokens")
    private Integer maxResponseTokens;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AgentStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Per-agent fallback reply (ADR 0005, C3); null means the platform default (V28). */
    @Column(name = "fallback_reply")
    private String fallbackReply;

    /** The tools the agent may use (ADR 0005, C4); empty by default (V29). */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "tool_names", nullable = false, columnDefinition = "text[]")
    private String[] toolNames = new String[0];

    /** Whether earlier turns' lookup results are left out of the context (V45, deferred R1). */
    @Column(name = "elide_past_lookups", nullable = false)
    private boolean elidePastLookups;

    /** How long the provider keeps the agent's prompt prefix cached (V50). */
    @Enumerated(EnumType.STRING)
    @Column(name = "prompt_cache_retention", nullable = false, length = 10)
    private PromptCacheRetention promptCacheRetention = PromptCacheRetention.SHORT;

    protected AgentEntity() {
        // for JPA
    }

    public AgentEntity(UUID id, UUID tenantId, String name, String systemPrompt, String modelProvider,
                       String modelName, Double temperature, Integer maxResponseTokens,
                       AgentStatus status, Instant createdAt, Instant updatedAt) {
        this(id, tenantId, name, systemPrompt, modelProvider, modelName, temperature, maxResponseTokens,
                status, null, createdAt, updatedAt);
    }

    public AgentEntity(UUID id, UUID tenantId, String name, String systemPrompt, String modelProvider,
                       String modelName, Double temperature, Integer maxResponseTokens,
                       AgentStatus status, String fallbackReply, Instant createdAt, Instant updatedAt) {
        this(id, tenantId, name, systemPrompt, modelProvider, modelName, temperature, maxResponseTokens,
                status, fallbackReply, new String[0], createdAt, updatedAt);
    }

    public AgentEntity(UUID id, UUID tenantId, String name, String systemPrompt, String modelProvider,
                       String modelName, Double temperature, Integer maxResponseTokens,
                       AgentStatus status, String fallbackReply, String[] toolNames, Instant createdAt,
                       Instant updatedAt) {
        this(id, tenantId, name, systemPrompt, modelProvider, modelName, temperature, maxResponseTokens,
                status, fallbackReply, toolNames, false, createdAt, updatedAt);
    }

    public AgentEntity(UUID id, UUID tenantId, String name, String systemPrompt, String modelProvider,
                       String modelName, Double temperature, Integer maxResponseTokens,
                       AgentStatus status, String fallbackReply, String[] toolNames, boolean elidePastLookups,
                       Instant createdAt, Instant updatedAt) {
        this(id, tenantId, name, systemPrompt, modelProvider, modelName, temperature, maxResponseTokens,
                status, fallbackReply, toolNames, elidePastLookups, PromptCacheRetention.SHORT, createdAt, updatedAt);
    }

    public AgentEntity(UUID id, UUID tenantId, String name, String systemPrompt, String modelProvider,
                       String modelName, Double temperature, Integer maxResponseTokens,
                       AgentStatus status, String fallbackReply, String[] toolNames, boolean elidePastLookups,
                       PromptCacheRetention promptCacheRetention, Instant createdAt, Instant updatedAt) {
        this.elidePastLookups = elidePastLookups;
        this.promptCacheRetention = promptCacheRetention;
        this.fallbackReply = fallbackReply;
        this.toolNames = toolNames;
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.systemPrompt = systemPrompt;
        this.modelProvider = modelProvider;
        this.modelName = modelName;
        this.temperature = temperature;
        this.maxResponseTokens = maxResponseTokens;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public String getModelProvider() {
        return modelProvider;
    }

    public String getModelName() {
        return modelName;
    }

    public Double getTemperature() {
        return temperature;
    }

    public Integer getMaxResponseTokens() {
        return maxResponseTokens;
    }

    public AgentStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isElidePastLookups() {
        return elidePastLookups;
    }

    public PromptCacheRetention getPromptCacheRetention() {
        return promptCacheRetention;
    }

    public String getFallbackReply() {
        return fallbackReply;
    }

    public String[] getToolNames() {
        return toolNames.clone();
    }
}
