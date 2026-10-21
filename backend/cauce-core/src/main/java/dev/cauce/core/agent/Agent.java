package dev.cauce.core.agent;

import dev.cauce.core.UuidGenerator;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
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

    /** Upper bound of a configured fallback reply (mirrors the V28 CHECK). */
    public static final int MAX_FALLBACK_REPLY_LENGTH = 1000;

    private final UUID id;
    private final UUID tenantId;
    private final String name;
    private final String systemPrompt;
    private final String modelProvider;
    private final String modelName;
    private final double temperature;
    private final int maxResponseTokens;
    private final AgentStatus status;
    private final String fallbackReply; // null: the platform default (ADR 0005, C3)
    private final Instant createdAt;
    private final Instant updatedAt;

    private Agent(UUID id, UUID tenantId, String name, String systemPrompt, String modelProvider,
                  String modelName, double temperature, int maxResponseTokens, AgentStatus status,
                  String fallbackReply, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.systemPrompt = systemPrompt;
        this.modelProvider = modelProvider;
        this.modelName = modelName;
        this.temperature = temperature;
        this.maxResponseTokens = maxResponseTokens;
        this.status = status;
        this.fallbackReply = fallbackReply;
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
                null,
                now,
                now);
    }

    /**
     * Returns a copy of this agent whose fallback reply (ADR 0005, C3) is {@code fallbackReply}:
     * the text the interlocutor receives when an invocation fails for good. {@code null} means
     * the platform default; a non-null text is stripped and must be non-blank and at most
     * {@value #MAX_FALLBACK_REPLY_LENGTH} characters.
     */
    public Agent withFallbackReply(String fallbackReply) {
        return new Agent(id, tenantId, name, systemPrompt, modelProvider, modelName, temperature,
                maxResponseTokens, status, validatedFallbackReply(fallbackReply), createdAt, updatedAt);
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
        return rehydrate(id, tenantId, name, systemPrompt, modelProvider, modelName, temperature,
                maxResponseTokens, status, null, createdAt, updatedAt);
    }

    /** Rebuilds an agent, its fallback reply included ({@code null}: platform default). */
    public static Agent rehydrate(UUID id, UUID tenantId, String name, String systemPrompt,
                                  String modelProvider, String modelName, Double temperature,
                                  Integer maxResponseTokens, AgentStatus status, String fallbackReply,
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
                fallbackReply, // nullable; the V28 CHECK guarantees its shape
                Objects.requireNonNull(createdAt, "createdAt"),
                Objects.requireNonNull(updatedAt, "updatedAt"));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }

    private static String validatedFallbackReply(String fallbackReply) {
        if (fallbackReply == null) {
            return null;
        }
        String stripped = fallbackReply.strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException("fallbackReply must not be blank");
        }
        if (stripped.length() > MAX_FALLBACK_REPLY_LENGTH) {
            throw new IllegalArgumentException("fallbackReply must be at most "
                    + MAX_FALLBACK_REPLY_LENGTH + " characters");
        }
        return stripped;
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

    /** The configured fallback reply; empty means the platform default (ADR 0005, C3). */
    public Optional<String> fallbackReply() {
        return Optional.ofNullable(fallbackReply);
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
