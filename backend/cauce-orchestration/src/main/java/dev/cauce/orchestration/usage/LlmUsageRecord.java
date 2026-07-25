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
 * not — pricing is a future versioned table and cost a view over these facts, so a tariff
 * change never rewrites the ledger. {@code provider}, {@code model}, and {@code finishReason}
 * are {@code String}s because their vocabularies are owned by the cauce-llm SPI.
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
                             String finishReason,
                             Instant createdAt) {

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
        if (inputTokens < 0 || outputTokens < 0 || totalTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
    }

    /** Mints a new record for one LLM call, stamping a UUIDv7 id and the current instant. */
    public static LlmUsageRecord create(UUID tenantId, UUID agentId, UUID conversationId,
                                        UUID invocationId, String provider, String model,
                                        int roundIndex, LlmUsage usage, String finishReason) {
        Objects.requireNonNull(usage, "usage must not be null");
        return new LlmUsageRecord(UuidGenerator.newV7(), tenantId, agentId, conversationId,
                invocationId, provider, model, roundIndex, usage.inputTokens(),
                usage.outputTokens(), usage.totalTokens(), finishReason, Instant.now());
    }
}
