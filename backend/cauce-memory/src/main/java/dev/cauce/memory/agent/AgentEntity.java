package dev.cauce.memory.agent;

import dev.cauce.core.agent.AgentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

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

    protected AgentEntity() {
        // for JPA
    }

    public AgentEntity(UUID id, UUID tenantId, String name, String systemPrompt, String modelProvider,
                       String modelName, Double temperature, Integer maxResponseTokens,
                       AgentStatus status, Instant createdAt, Instant updatedAt) {
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
}
