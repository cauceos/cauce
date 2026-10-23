package dev.cauce.orchestration.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.llm.model.LlmUsage;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LlmUsageRecordTest {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    private final UUID tenantId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final UUID invocationId = UUID.randomUUID();

    @Test
    void create_withValidArguments_mintsUuidV7AndCopiesUsage() {
        LlmUsageRecord record = LlmUsageRecord.create(tenantId, agentId, conversationId,
                invocationId, "anthropic", "claude-sonnet-4-7", 2, LlmUsage.of(7, 9), "STOP", NOW);

        assertThat(record.id()).isNotNull();
        assertThat(record.id().version()).isEqualTo(7);
        assertThat(record.tenantId()).isEqualTo(tenantId);
        assertThat(record.agentId()).isEqualTo(agentId);
        assertThat(record.conversationId()).isEqualTo(conversationId);
        assertThat(record.invocationId()).isEqualTo(invocationId);
        assertThat(record.provider()).isEqualTo("anthropic");
        assertThat(record.model()).isEqualTo("claude-sonnet-4-7");
        assertThat(record.roundIndex()).isEqualTo(2);
        assertThat(record.inputTokens()).isEqualTo(7);
        assertThat(record.outputTokens()).isEqualTo(9);
        assertThat(record.totalTokens()).isEqualTo(16);
        assertThat(record.finishReason()).isEqualTo("STOP");
        assertThat(record.createdAt()).isEqualTo(NOW);
    }

    @Test
    void create_whenNullUsage_throwsNpe() {
        assertThatThrownBy(() -> LlmUsageRecord.create(tenantId, agentId, conversationId,
                invocationId, "anthropic", "claude-sonnet-4-7", 0, null, "STOP", NOW))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("usage");
    }

    @Test
    void create_whenNullTenantId_throwsNpe() {
        assertThatThrownBy(() -> LlmUsageRecord.create(null, agentId, conversationId,
                invocationId, "anthropic", "claude-sonnet-4-7", 0, LlmUsage.of(1, 1), "STOP", NOW))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    void create_whenBlankProvider_throwsIllegalArgument() {
        assertThatThrownBy(() -> LlmUsageRecord.create(tenantId, agentId, conversationId,
                invocationId, "  ", "claude-sonnet-4-7", 0, LlmUsage.of(1, 1), "STOP", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider");
    }

    @Test
    void create_whenBlankModel_throwsIllegalArgument() {
        assertThatThrownBy(() -> LlmUsageRecord.create(tenantId, agentId, conversationId,
                invocationId, "anthropic", "", 0, LlmUsage.of(1, 1), "STOP", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
    }

    @Test
    void create_whenBlankFinishReason_throwsIllegalArgument() {
        assertThatThrownBy(() -> LlmUsageRecord.create(tenantId, agentId, conversationId,
                invocationId, "anthropic", "claude-sonnet-4-7", 0, LlmUsage.of(1, 1), "", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finishReason");
    }

    @Test
    void create_whenNegativeRoundIndex_throwsIllegalArgument() {
        assertThatThrownBy(() -> LlmUsageRecord.create(tenantId, agentId, conversationId,
                invocationId, "anthropic", "claude-sonnet-4-7", -1, LlmUsage.of(1, 1), "STOP", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("roundIndex");
    }

    @Test
    void constructor_whenNegativeTokens_throwsIllegalArgument() {
        assertThatThrownBy(() -> new LlmUsageRecord(UUID.randomUUID(), tenantId, agentId,
                conversationId, invocationId, "anthropic", "claude-sonnet-4-7", 0,
                -1, 0, 0, 0, 0, "STOP", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token");
    }
}
