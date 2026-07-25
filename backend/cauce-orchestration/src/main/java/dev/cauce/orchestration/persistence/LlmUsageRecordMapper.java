package dev.cauce.orchestration.persistence;

import dev.cauce.orchestration.usage.LlmUsageRecord;
import org.springframework.stereotype.Component;

/**
 * Hand-written bidirectional mapping between the domain {@link LlmUsageRecord} and its JPA
 * {@link LlmUsageRecordEntity}. No external mapping library is used.
 */
@Component
public final class LlmUsageRecordMapper {

    public LlmUsageRecordEntity toEntity(LlmUsageRecord record) {
        return new LlmUsageRecordEntity(
                record.id(),
                record.tenantId(),
                record.agentId(),
                record.conversationId(),
                record.invocationId(),
                record.provider(),
                record.model(),
                record.roundIndex(),
                record.inputTokens(),
                record.outputTokens(),
                record.totalTokens(),
                record.finishReason(),
                record.createdAt());
    }

    public LlmUsageRecord toDomain(LlmUsageRecordEntity entity) {
        return new LlmUsageRecord(
                entity.getId(),
                entity.getTenantId(),
                entity.getAgentId(),
                entity.getConversationId(),
                entity.getInvocationId(),
                entity.getProvider(),
                entity.getModel(),
                entity.getRoundIndex(),
                entity.getInputTokens(),
                entity.getOutputTokens(),
                entity.getTotalTokens(),
                entity.getFinishReason(),
                entity.getCreatedAt());
    }
}
