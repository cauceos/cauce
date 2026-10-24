package dev.cauce.orchestration.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA persistence mapping for one LLM usage row. Infrastructure detail of
 * cauce-orchestration; the domain type is
 * {@link dev.cauce.orchestration.usage.LlmUsageRecord}, converted by
 * {@link LlmUsageRecordMapper}. Rows are insert-only immutable facts — every column is
 * {@code updatable = false}.
 */
@Entity
@Table(name = "llm_usage_records")
public class LlmUsageRecordEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "agent_id", nullable = false, updatable = false)
    private UUID agentId;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(name = "invocation_id", nullable = false, updatable = false)
    private UUID invocationId;

    @Column(name = "provider", nullable = false, updatable = false, length = 20)
    private String provider;

    @Column(name = "model", nullable = false, updatable = false, length = 100)
    private String model;

    @Column(name = "round_index", nullable = false, updatable = false)
    private int roundIndex;

    @Column(name = "input_tokens", nullable = false, updatable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false, updatable = false)
    private int outputTokens;

    @Column(name = "total_tokens", nullable = false, updatable = false)
    private int totalTokens;

    @Column(name = "cache_read_tokens", nullable = false, updatable = false)
    private int cacheReadTokens;

    @Column(name = "cache_write_tokens", nullable = false, updatable = false)
    private int cacheWriteTokens;

    @Column(name = "cache_write_long_tokens", nullable = false, updatable = false)
    private int cacheWriteLongTokens;

    @Column(name = "finish_reason", nullable = false, updatable = false, length = 20)
    private String finishReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** The seconds of audio a transcription call transcribed (V56); 0 for a model call. */
    @Column(name = "audio_seconds", nullable = false, updatable = false)
    private int audioSeconds;

    protected LlmUsageRecordEntity() {
        // for JPA
    }

    public LlmUsageRecordEntity(UUID id, UUID tenantId, UUID agentId, UUID conversationId,
                                UUID invocationId, String provider, String model, int roundIndex,
                                int inputTokens, int outputTokens, int totalTokens,
                                int cacheReadTokens, int cacheWriteTokens, int cacheWriteLongTokens, String finishReason,
                                Instant createdAt) {
        this(id, tenantId, agentId, conversationId, invocationId, provider, model, roundIndex, inputTokens,
                outputTokens, totalTokens, cacheReadTokens, cacheWriteTokens, cacheWriteLongTokens, finishReason,
                createdAt, 0);
    }

    public LlmUsageRecordEntity(UUID id, UUID tenantId, UUID agentId, UUID conversationId,
                                UUID invocationId, String provider, String model, int roundIndex,
                                int inputTokens, int outputTokens, int totalTokens,
                                int cacheReadTokens, int cacheWriteTokens, int cacheWriteLongTokens, String finishReason,
                                Instant createdAt, int audioSeconds) {
        this.audioSeconds = audioSeconds;
        this.id = id;
        this.tenantId = tenantId;
        this.agentId = agentId;
        this.conversationId = conversationId;
        this.invocationId = invocationId;
        this.provider = provider;
        this.model = model;
        this.roundIndex = roundIndex;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = totalTokens;
        this.cacheReadTokens = cacheReadTokens;
        this.cacheWriteTokens = cacheWriteTokens;
        this.cacheWriteLongTokens = cacheWriteLongTokens;
        this.finishReason = finishReason;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public int getAudioSeconds() {
        return audioSeconds;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public UUID getInvocationId() {
        return invocationId;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public int getRoundIndex() {
        return roundIndex;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public int getCacheReadTokens() {
        return cacheReadTokens;
    }

    public int getCacheWriteTokens() {
        return cacheWriteTokens;
    }

    public int getCacheWriteLongTokens() {
        return cacheWriteLongTokens;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
