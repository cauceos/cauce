package dev.cauce.orchestration.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.llm.model.LlmUsage;
import dev.cauce.orchestration.usage.LlmUsageRecord;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LlmUsageRecordMapperTest {

    private final LlmUsageRecordMapper mapper = new LlmUsageRecordMapper();

    @Test
    void roundTrip_preservesAllFields() {
        LlmUsageRecord original = LlmUsageRecord.create(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "mistral", "mistral-large-latest", 3,
                LlmUsage.of(120, 45), "TOOL_USE");

        LlmUsageRecord roundTripped = mapper.toDomain(mapper.toEntity(original));

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void toEntity_mapsEveryColumn() {
        LlmUsageRecord record = LlmUsageRecord.create(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "anthropic", "claude-sonnet-4-7", 0,
                LlmUsage.of(7, 9), "STOP");

        LlmUsageRecordEntity entity = mapper.toEntity(record);

        assertThat(entity.getId()).isEqualTo(record.id());
        assertThat(entity.getTenantId()).isEqualTo(record.tenantId());
        assertThat(entity.getAgentId()).isEqualTo(record.agentId());
        assertThat(entity.getConversationId()).isEqualTo(record.conversationId());
        assertThat(entity.getInvocationId()).isEqualTo(record.invocationId());
        assertThat(entity.getProvider()).isEqualTo("anthropic");
        assertThat(entity.getModel()).isEqualTo("claude-sonnet-4-7");
        assertThat(entity.getRoundIndex()).isZero();
        assertThat(entity.getInputTokens()).isEqualTo(7);
        assertThat(entity.getOutputTokens()).isEqualTo(9);
        assertThat(entity.getTotalTokens()).isEqualTo(16);
        assertThat(entity.getFinishReason()).isEqualTo("STOP");
        assertThat(entity.getCreatedAt()).isEqualTo(record.createdAt());
    }
}
