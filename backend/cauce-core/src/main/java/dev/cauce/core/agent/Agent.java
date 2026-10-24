package dev.cauce.core.agent;

import dev.cauce.core.UuidGenerator;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
 * configuration used when building invocations. {@code temperature} is a sampling parameter:
 * it is sent to the provider only when the agent configures it — unset, the model's own
 * default applies, and a model that does not accept the parameter at all keeps working.
 * {@code maxResponseTokens} is always set ({@link #DEFAULT_MAX_RESPONSE_TOKENS} when null):
 * providers require a bound on the reply.
 *
 * <p>{@code elidePastLookups}, off by default, leaves out of the model's context the results of
 * lookups made in earlier turns (the profile, the menu, availability), keeping each call and a
 * marker in its place: the agent looks them up again when it needs them. An experiment measured
 * with the eval harness before it becomes a default (deferred R1).
 *
 * <p>{@code promptCacheRetention}, {@link PromptCacheRetention#SHORT} by default, is how long the
 * provider is asked to keep the agent's prompt prefix cached (ADR 0010, S9).
 */
public final class Agent {

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
    private final Double temperature; // null: not configured, the model's default
    private final int maxResponseTokens;
    private final AgentStatus status;
    private final String fallbackReply; // null: the platform default (ADR 0005, C3)
    private final String unsupportedMessageReply; // null: the channel's default (ADR 0006, W5)
    private final List<String> toolNames; // the granted tools; empty by default (ADR 0005, C4)
    private final boolean elidePastLookups; // off: the full history, as always
    private final PromptCacheRetention promptCacheRetention; // SHORT unless the agent asks for LONG
    private final Instant createdAt;
    private final Instant updatedAt;

    private Agent(UUID id, UUID tenantId, String name, String systemPrompt, String modelProvider,
                  String modelName, Double temperature, int maxResponseTokens, AgentStatus status,
                  String fallbackReply, String unsupportedMessageReply, List<String> toolNames,
                  boolean elidePastLookups, PromptCacheRetention promptCacheRetention, Instant createdAt,
                  Instant updatedAt) {
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
        this.unsupportedMessageReply = unsupportedMessageReply;
        this.toolNames = toolNames;
        this.elidePastLookups = elidePastLookups;
        this.promptCacheRetention = promptCacheRetention;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Creates a new DRAFT agent with the default LLM configuration. */
    public static Agent create(UUID tenantId, String name, String systemPrompt,
                               String modelProvider, String modelName) {
        return create(tenantId, name, systemPrompt, modelProvider, modelName, null, null);
    }

    /**
     * Creates a new DRAFT agent owned by {@code tenantId}. A null {@code temperature} leaves it
     * unconfigured (never sent); a null {@code maxResponseTokens} falls back to the default;
     * non-null values are validated
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
                validatedTemperature(temperature),
                validatedMaxResponseTokens(resolveMaxResponseTokens(maxResponseTokens)),
                AgentStatus.DRAFT,
                null,
                null,
                List.of(),
                false,
                PromptCacheRetention.SHORT,
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
                maxResponseTokens, status, validatedFallbackReply(fallbackReply), unsupportedMessageReply, toolNames,
                elidePastLookups, promptCacheRetention,
                createdAt, updatedAt);
    }

    /**
     * Returns a copy of this agent whose reply to a message its channel delivered but Cauce does not
     * read (a voice note, a photo; ADR 0006, W5) is {@code unsupportedMessageReply}. {@code null}
     * means the channel's platform default; a non-null text follows {@link #withFallbackReply}.
     */
    public Agent withUnsupportedMessageReply(String unsupportedMessageReply) {
        return new Agent(id, tenantId, name, systemPrompt, modelProvider, modelName, temperature,
                maxResponseTokens, status, fallbackReply,
                validatedReply(unsupportedMessageReply, "unsupportedMessageReply"), toolNames, elidePastLookups,
                promptCacheRetention, createdAt, updatedAt);
    }

    /**
     * Returns a copy of this agent granted exactly {@code tools} (ADR 0005, C4), in the given
     * order, keeping its timestamps (the creation path). Each name is stripped and must be
     * non-blank, and a name may appear once. Whether a tool exists is not the domain's concern:
     * the application service validates the names against the tool catalog.
     */
    public Agent withTools(Collection<String> tools) {
        return new Agent(id, tenantId, name, systemPrompt, modelProvider, modelName, temperature,
                maxResponseTokens, status, fallbackReply, unsupportedMessageReply, validatedToolNames(tools), elidePastLookups,
                promptCacheRetention, createdAt,
                updatedAt);
    }

    /** {@link #withTools} as a modification of an existing agent: {@code updatedAt} moves to now. */
    public Agent retooled(Collection<String> tools) {
        return new Agent(id, tenantId, name, systemPrompt, modelProvider, modelName, temperature,
                maxResponseTokens, status, fallbackReply, unsupportedMessageReply, validatedToolNames(tools), elidePastLookups,
                promptCacheRetention, createdAt,
                Instant.now());
    }

    /**
     * Returns a copy of this agent that elides past lookups, or not ({@code updatedAt} moves to now):
     * see the class description.
     */
    public Agent withPastLookupsElided(boolean elide) {
        return new Agent(id, tenantId, name, systemPrompt, modelProvider, modelName, temperature,
                maxResponseTokens, status, fallbackReply, unsupportedMessageReply, toolNames, elide, promptCacheRetention,
                createdAt,
                Instant.now());
    }

    /**
     * Returns a copy of this agent whose prompt prefix the provider keeps for {@code retention}
     * ({@code updatedAt} moves to now): see the class description.
     */
    public Agent withPromptCacheRetention(PromptCacheRetention retention) {
        return new Agent(id, tenantId, name, systemPrompt, modelProvider, modelName, temperature,
                maxResponseTokens, status, fallbackReply, unsupportedMessageReply, toolNames, elidePastLookups,
                Objects.requireNonNull(retention, "retention"), createdAt, Instant.now());
    }

    /**
     * Returns a copy of this agent with its configuration edited: each non-null argument replaces
     * the current value, a null one keeps it; {@code updatedAt} moves to now. {@code name} must be
     * non-blank; {@code systemPrompt} may be empty, as at creation; {@code fallbackReply} follows
     * {@link #withFallbackReply}.
     */
    public Agent edited(String name, String systemPrompt, String fallbackReply) {
        return edited(name, systemPrompt, fallbackReply, null);
    }

    /** {@link #edited(String, String, String)}, and {@code unsupportedMessageReply} the same way. */
    public Agent edited(String name, String systemPrompt, String fallbackReply, String unsupportedMessageReply) {
        return new Agent(id, tenantId,
                name == null ? this.name : requireText(name, "name"),
                systemPrompt == null ? this.systemPrompt : systemPrompt,
                modelProvider, modelName, temperature, maxResponseTokens, status,
                fallbackReply == null ? this.fallbackReply : validatedFallbackReply(fallbackReply),
                unsupportedMessageReply == null ? this.unsupportedMessageReply
                        : validatedReply(unsupportedMessageReply, "unsupportedMessageReply"),
                toolNames, elidePastLookups, promptCacheRetention, createdAt, Instant.now());
    }

    /**
     * Rebuilds an agent from already-persisted state. For the persistence layer only. A null
     * {@code maxResponseTokens} (a pre-V7 row) is coalesced to the
     * default; values are not range-checked here since the database CHECK constraints already
     * guarantee them.
     */
    public static Agent rehydrate(UUID id, UUID tenantId, String name, String systemPrompt,
                                  String modelProvider, String modelName, Double temperature,
                                  Integer maxResponseTokens, AgentStatus status,
                                  Instant createdAt, Instant updatedAt) {
        return rehydrate(id, tenantId, name, systemPrompt, modelProvider, modelName, temperature,
                maxResponseTokens, status, null, List.of(), createdAt, updatedAt);
    }

    /**
     * Rebuilds an agent with its fallback reply ({@code null}: platform default) and granted tool
     * names, eliding no past lookups.
     */
    public static Agent rehydrate(UUID id, UUID tenantId, String name, String systemPrompt,
                                  String modelProvider, String modelName, Double temperature,
                                  Integer maxResponseTokens, AgentStatus status, String fallbackReply,
                                  List<String> toolNames, Instant createdAt, Instant updatedAt) {
        return rehydrate(id, tenantId, name, systemPrompt, modelProvider, modelName, temperature,
                maxResponseTokens, status, fallbackReply, toolNames, false, createdAt, updatedAt);
    }

    /** Rebuilds an agent with whether it elides past lookups, and the short prompt cache. */
    public static Agent rehydrate(UUID id, UUID tenantId, String name, String systemPrompt,
                                  String modelProvider, String modelName, Double temperature,
                                  Integer maxResponseTokens, AgentStatus status, String fallbackReply,
                                  List<String> toolNames, boolean elidePastLookups, Instant createdAt,
                                  Instant updatedAt) {
        return rehydrate(id, tenantId, name, systemPrompt, modelProvider, modelName, temperature,
                maxResponseTokens, status, fallbackReply, toolNames, elidePastLookups, PromptCacheRetention.SHORT,
                createdAt, updatedAt);
    }

    /** Rebuilds an agent with its prompt cache retention, and the channels' default unsupported reply. */
    public static Agent rehydrate(UUID id, UUID tenantId, String name, String systemPrompt,
                                  String modelProvider, String modelName, Double temperature,
                                  Integer maxResponseTokens, AgentStatus status, String fallbackReply,
                                  List<String> toolNames, boolean elidePastLookups,
                                  PromptCacheRetention promptCacheRetention, Instant createdAt,
                                  Instant updatedAt) {
        return rehydrate(id, tenantId, name, systemPrompt, modelProvider, modelName, temperature,
                maxResponseTokens, status, fallbackReply, null, toolNames, elidePastLookups, promptCacheRetention,
                createdAt, updatedAt);
    }

    /** Rebuilds an agent with its full configuration, including its reply to an unsupported message. */
    public static Agent rehydrate(UUID id, UUID tenantId, String name, String systemPrompt,
                                  String modelProvider, String modelName, Double temperature,
                                  Integer maxResponseTokens, AgentStatus status, String fallbackReply,
                                  String unsupportedMessageReply, List<String> toolNames, boolean elidePastLookups,
                                  PromptCacheRetention promptCacheRetention, Instant createdAt,
                                  Instant updatedAt) {
        return new Agent(
                Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(tenantId, "tenantId"),
                Objects.requireNonNull(name, "name"),
                Objects.requireNonNull(systemPrompt, "systemPrompt"),
                Objects.requireNonNull(modelProvider, "modelProvider"),
                Objects.requireNonNull(modelName, "modelName"),
                temperature,
                resolveMaxResponseTokens(maxResponseTokens),
                Objects.requireNonNull(status, "status"),
                fallbackReply, // nullable; the V28 CHECK guarantees its shape
                unsupportedMessageReply, // nullable; the V55 CHECK guarantees its shape
                List.copyOf(Objects.requireNonNull(toolNames, "toolNames")),
                elidePastLookups,
                Objects.requireNonNull(promptCacheRetention, "promptCacheRetention"),
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
        return validatedReply(fallbackReply, "fallbackReply");
    }

    /** A configured reply: null, or stripped, non-blank and at most {@value #MAX_FALLBACK_REPLY_LENGTH} characters. */
    private static String validatedReply(String reply, String field) {
        if (reply == null) {
            return null;
        }
        String stripped = reply.strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (stripped.length() > MAX_FALLBACK_REPLY_LENGTH) {
            throw new IllegalArgumentException(field + " must be at most "
                    + MAX_FALLBACK_REPLY_LENGTH + " characters");
        }
        return stripped;
    }

    private static List<String> validatedToolNames(Collection<String> tools) {
        Objects.requireNonNull(tools, "tools");
        Set<String> seen = new LinkedHashSet<>();
        for (String tool : tools) {
            if (tool == null || tool.isBlank()) {
                throw new IllegalArgumentException("tool names must not be blank");
            }
            if (!seen.add(tool.strip())) {
                throw new IllegalArgumentException("tool '" + tool.strip() + "' is granted twice");
            }
        }
        return List.copyOf(seen);
    }


    private static int resolveMaxResponseTokens(Integer maxResponseTokens) {
        return maxResponseTokens == null ? DEFAULT_MAX_RESPONSE_TOKENS : maxResponseTokens;
    }

    private static Double validatedTemperature(Double temperature) {
        if (temperature != null && (temperature < 0.0 || temperature > 1.0)) {
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

    /** The configured sampling temperature; empty means not configured: the model's default. */
    public Optional<Double> temperature() {
        return Optional.ofNullable(temperature);
    }

    public int maxResponseTokens() {
        return maxResponseTokens;
    }

    /** The configured fallback reply; empty means the platform default (ADR 0005, C3). */
    public Optional<String> fallbackReply() {
        return Optional.ofNullable(fallbackReply);
    }

    /**
     * The reply to a message its channel delivered but Cauce does not read (ADR 0006, W5); empty
     * means the channel's platform default.
     */
    public Optional<String> unsupportedMessageReply() {
        return Optional.ofNullable(unsupportedMessageReply);
    }

    /** The tools this agent may use, in grant order; empty means none (ADR 0005, C4). */
    public List<String> toolNames() {
        return toolNames;
    }

    /** Whether earlier turns' lookup results are left out of the model's context (deferred R1). */
    public boolean elidesPastLookups() {
        return elidePastLookups;
    }

    /** How long the provider is asked to keep the agent's prompt prefix cached (ADR 0010, S9). */
    public PromptCacheRetention promptCacheRetention() {
        return promptCacheRetention;
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
