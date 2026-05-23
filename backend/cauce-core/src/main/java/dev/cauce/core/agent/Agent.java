package dev.cauce.core.agent;

import dev.cauce.core.UuidGenerator;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A configurable conversational agent owned by a tenant.
 *
 * <p>Pure domain type: no persistence or framework dependencies. Immutable; created
 * via {@link #create} with status {@link AgentStatus#DRAFT} and a time-ordered
 * UUIDv7 id. Status transitions and updates arrive in a later commit.
 *
 * <p>{@code modelProvider} is a free-form String identifier (e.g. {@code "anthropic"}).
 * The domain deliberately does not enumerate providers: the set of valid providers is
 * owned by the (not-yet-implemented) cauce-llm SPI, keeping the core free of
 * provider-specific knowledge (architectural invariant: LLM providers are pluggable).
 * Until the SPI exists, the application service validates against a temporary set.
 */
public final class Agent {

    private final UUID id;
    private final UUID tenantId;
    private final String name;
    private final String systemPrompt;
    private final String modelProvider;
    private final String modelName;
    private final AgentStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Agent(UUID id, UUID tenantId, String name, String systemPrompt, String modelProvider,
                  String modelName, AgentStatus status, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.systemPrompt = systemPrompt;
        this.modelProvider = modelProvider;
        this.modelName = modelName;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Creates a new DRAFT agent owned by {@code tenantId}. */
    public static Agent create(UUID tenantId, String name, String systemPrompt,
                               String modelProvider, String modelName) {
        Instant now = Instant.now();
        return new Agent(
                UuidGenerator.newV7(),
                Objects.requireNonNull(tenantId, "tenantId"),
                requireText(name, "name"),
                Objects.requireNonNull(systemPrompt, "systemPrompt"), // may be empty for a draft
                requireText(modelProvider, "modelProvider"),
                requireText(modelName, "modelName"),
                AgentStatus.DRAFT,
                now,
                now);
    }

    /** Rebuilds an agent from already-persisted state. For the persistence layer only. */
    public static Agent rehydrate(UUID id, UUID tenantId, String name, String systemPrompt,
                                  String modelProvider, String modelName, AgentStatus status,
                                  Instant createdAt, Instant updatedAt) {
        return new Agent(
                Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(tenantId, "tenantId"),
                Objects.requireNonNull(name, "name"),
                Objects.requireNonNull(systemPrompt, "systemPrompt"),
                Objects.requireNonNull(modelProvider, "modelProvider"),
                Objects.requireNonNull(modelName, "modelName"),
                Objects.requireNonNull(status, "status"),
                Objects.requireNonNull(createdAt, "createdAt"),
                Objects.requireNonNull(updatedAt, "updatedAt"));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public String name() {
        return name;
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public String modelProvider() {
        return modelProvider;
    }

    public String modelName() {
        return modelName;
    }

    public AgentStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Agent other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        // systemPrompt is intentionally omitted (potentially large / sensitive).
        return "Agent[id=%s, tenantId=%s, name=%s, status=%s, modelProvider=%s, modelName=%s]"
                .formatted(id, tenantId, name, status, modelProvider, modelName);
    }
}
