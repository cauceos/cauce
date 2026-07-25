package dev.cauce.orchestration.usage;

import static org.mockito.Mockito.verify;

import dev.cauce.llm.model.LlmUsage;
import dev.cauce.orchestration.persistence.LlmUsageRecordEntity;
import dev.cauce.orchestration.persistence.LlmUsageRecordMapper;
import dev.cauce.orchestration.persistence.LlmUsageRecordRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class LlmUsageRecorderTest {

    private final LlmUsageRecordRepository repository =
            Mockito.mock(LlmUsageRecordRepository.class);
    private final LlmUsageRecordMapper mapper = new LlmUsageRecordMapper();
    private final LlmUsageRecorder recorder = new LlmUsageRecorder(repository, mapper);

    @Test
    void record_persistsMappedEntity() {
        LlmUsageRecord record = LlmUsageRecord.create(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "anthropic", "claude-sonnet-4-7", 0,
                LlmUsage.of(7, 9), "STOP");

        recorder.record(record);

        verify(repository).save(Mockito.argThat((LlmUsageRecordEntity entity) ->
                entity.getId().equals(record.id())
                        && entity.getTenantId().equals(record.tenantId())
                        && entity.getTotalTokens() == 16));
    }
}
