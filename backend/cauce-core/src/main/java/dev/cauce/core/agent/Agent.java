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
 *
 * <p>{@code temperature} and {@code maxResponseTokens} are the per-agent LLM call
 * configuration used when building invocations. They are always non-null on the domain
 * object: the factories apply {@link #DEFAULT_TEMPERATURE} / {@link #DEFAULT_MAX_RESPONSE_TOKENS}
 * when a null is supplied (e.g. a pre-V7 row read back from the database). The domain is
 * the single owner of the "null means default" policy.
 */
public final class Agent {

    /** Default sampling temperature applied when none is configured. */
    public static final double DEFAULT_TEMPERATURE = 0.7;

    /** Default maximum response tokens applied when none is configured. */
    public static final int DEFAULT_MAX_RESPONSE_TOKENS = 4096;

    private final UUID id;
    private final UUID tenantId;
    private final String name;
    private final String systemPrompt;
    private final String modelProvider;
    private final String modelName;
    private final double temperature;
    private final int maxResponseTokens;
    private final AgentStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Agent(UUID id, UUID tenantId, String name, String systemPrompt, String modelProvider,
                  String modelName, double temperature, int maxResponseTokens, AgentStatus status,
                  Instant createdAt, Instant updatedAt) {
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

    /** Creates a new DRAFT agent with the default LLM configuration. */
    public static Agent create(UUID tenantId, String name, String systemPrompt,
                               String modelProvider, String modelName) {
        return create(tenantId, name, systemPrompt, modelProvider, modelName, null, null);
    }

    /**
     * Creates a new DRAFT agent owned by {@code tenantId}. A null {@code temperature} or
     * {@code maxResponseTokens} falls back to the default; non-null values are validated
     * ({@code temperature} within [0.0, 1.0], {@code maxResponseTokens} positive).
     */
    public static Agent create(UUID tenantId, String name, String systemPrompt,
                               String modelProvider, String modelName,
                               Double temperature, Integer maxResponseTokens) {
        Instant now = Instant.now();
        return new Agent(
                UuidGenerator.newV7(),
                Objects.requireNonNull(tenantId, "tenantId"),
                requireText(name, "name"),
                Objects.requireNonNull(systemPrompt, "systemPrompt"), // may be empty for a draft
                requireText(modelProvider, "modelProvider"),
                requireText(modelName, "modelName"),
                validatedTemperature(resolveTemperature(temperature)),
                validatedMaxResponseTokens(resolveMaxResponseTokens(maxResponseTokens)),
                AgentStatus.DRAFT,
                now,
                now);
    }

    /**
     * Rebuilds an agent from already-persisted state. For the persistence layer only. A null
     * {@code temperature} or {@code maxResponseTokens} (a pre-V7 row) is coalesced to the
     * default; values are not range-checked here since the database CHECK constraints already
     * guarantee them.
     */
    public static Agent rehydrate(UUID id, UUID tenantId, String name, String systemPrompt,
                                  String modelProvider, String modelName, Double temperature,
                                  Integer maxResponseTokens, AgentStatus status,
                                  Instant createdAt, Instant updatedAt) {
        return new Agent(
                Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(tenantId, "tenantId"),
                Objects.requireNonNull(name, "name"),
                Objects.requireNonNull(systemPrompt, "systemPrompt"),
                Objects.requireNonNull(modelProvider, "modelProvider"),
                Objects.requireNonNull(modelName, "modelName"),
                resolveTemperature(temperature),
                resolveMaxResponseTokens(maxResponseTokens),
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

    private static double resolveTemperature(Double temperature) {
        return temperature == null ? DEFAULT_TEMPERATURE : temperature;
    }

    private static int resolveMaxResponseTokens(Integer maxResponseTokens) {
        return maxResponseTokens == null ? DEFAULT_MAX_RESPONSE_TOKENS : maxResponseTokens;
    }

    private static double validatedTemperature(double temperature) {
        if (temperature < 0.0 || temperature > 1.0) {
            throw new IllegalArgumentException(
                    "temperature must be within [0.0, 1.0] but was " + temperature);
        }
        return temperature;
    }

    private static int validatedMaxResponseTokens(int maxResponseTokens) {
        if (maxResponseTokens <= 0) {
            throw new IllegalArgumentException(
                    "maxResponseTokens must be positive but was " + maxResponseTokens);
        }
        return maxResponseTokens;
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

    public double temperature() {
        return temperature;
    }

    public int maxResponseTokens() {
        return maxResponseTokens;
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
        return "Agent[id=%s, tenantId=%s, name=%s, status=%s, modelProvider=%s, modelName=%s, "
                + "temperature=%s, maxResponseTokens=%d]"
                .formatted(id, tenantId, name, status, modelProvider, modelName,
                        temperature, maxResponseTokens);
    }
}
