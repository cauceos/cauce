package dev.cauce.orchestration.usage;

import dev.cauce.core.UuidGenerator;
import dev.cauce.llm.model.LlmUsage;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One LLM call's token usage, attributed to the tenant that owns the agent. A multi-round
 * agentic invocation produces one record per round ({@code roundIndex}); a worker retry
 * re-runs already-executed rounds and legitimately repeats {@code (invocationId, roundIndex)}
 * — every record is one real, provider-billed call.
 *
 * <p>Immutable billing fact: tokens, provider, and model are persisted; cost is deliberately
 * not — pricing is the versioned price table and cost the {@code llm_usage_costs} view (ADR 0010),
 * so a tariff change never rewrites the ledger. {@code inputTokens} is the whole prompt;
 * {@code cacheReadTokens} and {@code cacheWriteTokens} are the parts of it the provider served from
 * or wrote to its prompt cache, priced differently, so they are facts too, and
 * {@code cacheWriteLongTokens} the part of the writes made for a long retention, dearer still (V49).
 * {@code audioSeconds} is the audio a transcription call transcribed (ADR 0012, V4; V56), 0 for a
 * model call: a transcription is a record with no tokens. {@code provider}, {@code model}, and
 * {@code finishReason} are {@code String}s because their vocabularies are owned by the cauce-llm SPI.
 *
 * <p>Pure domain type: no persistence or framework dependencies.
 */
public record LlmUsageRecord(UUID id,
                             UUID tenantId,
                             UUID agentId,
                             UUID conversationId,
                             UUID invocationId,
                             String provider,
                             String model,
                             int roundIndex,
                             int inputTokens,
                             int outputTokens,
                             int totalTokens,
                             int cacheReadTokens,
                             int cacheWriteTokens,
                             int cacheWriteLongTokens,
                             String finishReason,
                             Instant createdAt,
                             int audioSeconds) {

    /** What a transcription record says as its finish reason. */
    public static final String TRANSCRIBED = "TRANSCRIBED";

    /** A model call's record: no audio. */
    public LlmUsageRecord(UUID id, UUID tenantId, UUID agentId, UUID conversationId, UUID invocationId,
                          String provider, String model, int roundIndex, int inputTokens, int outputTokens,
                          int totalTokens, int cacheReadTokens, int cacheWriteTokens, int cacheWriteLongTokens,
                          String finishReason, Instant createdAt) {
        this(id, tenantId, agentId, conversationId, invocationId, provider, model, roundIndex, inputTokens,
                outputTokens, totalTokens, cacheReadTokens, cacheWriteTokens, cacheWriteLongTokens, finishReason,
                createdAt, 0);
    }

    public LlmUsageRecord {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(conversationId, "conversationId must not be null");
        Objects.requireNonNull(invocationId, "invocationId must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(finishReason, "finishReason must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        if (model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (finishReason.isBlank()) {
            throw new IllegalArgumentException("finishReason must not be blank");
        }
        if (roundIndex < 0) {
            throw new IllegalArgumentException("roundIndex must not be negative");
        }
        if (inputTokens < 0 || outputTokens < 0 || totalTokens < 0 || cacheReadTokens < 0 || cacheWriteTokens < 0
                || cacheWriteLongTokens < 0 || cacheWriteLongTokens > cacheWriteTokens) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
        if (audioSeconds < 0) {
            throw new IllegalArgumentException("audioSeconds must not be negative");
        }
    }

    /**
     * Mints the record of one transcription of {@code audioSeconds} of a voice note, by
     * {@code provider}'s {@code model}: no tokens, round 0, {@link #TRANSCRIBED}. {@code now} comes
     * from the application clock, the database's (ADR 0008).
     */
    public static LlmUsageRecord transcription(UUID tenantId, UUID agentId, UUID conversationId, UUID invocationId,
                                               String provider, String model, int audioSeconds, Instant now) {
        return new LlmUsageRecord(UuidGenerator.newV7(), tenantId, agentId, conversationId, invocationId, provider,
                model, 0, 0, 0, 0, 0, 0, 0, TRANSCRIBED, now, audioSeconds);
    }

    /**
     * Mints a new record for one LLM call, stamping a UUIDv7 id. {@code now} comes from the
     * application clock, the database's (ADR 0008): the day and month a call is billed to, and
     * the spend limit, are judged on it.
     */
    public static LlmUsageRecord create(UUID tenantId, UUID agentId, UUID conversationId,
                                        UUID invocationId, String provider, String model,
                                        int roundIndex, LlmUsage usage, String finishReason, Instant now) {
        Objects.requireNonNull(usage, "usage must not be null");
        return new LlmUsageRecord(UuidGenerator.newV7(), tenantId, agentId, conversationId,
                invocationId, provider, model, roundIndex, usage.inputTokens(),
                usage.outputTokens(), usage.totalTokens(), usage.cacheReadTokens(), usage.cacheWriteTokens(),
                usage.cacheWriteLongTokens(),
                finishReason, now);
    }
}
