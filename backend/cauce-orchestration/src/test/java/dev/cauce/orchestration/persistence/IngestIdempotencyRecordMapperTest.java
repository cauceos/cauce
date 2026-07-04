package dev.cauce.orchestration.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.orchestration.IngestIdempotencyRecord;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IngestIdempotencyRecordMapperTest {

    private final IngestIdempotencyRecordMapper mapper = new IngestIdempotencyRecordMapper();

    @Test
    void toDomain_completeRow_preservesEveryField() {
        UUID id = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-07-04T10:00:00Z");
        IngestIdempotencyRecordEntity entity = new IngestIdempotencyRecordEntity(
                id, agentId, "wamid-1", conversationId, messageId, invocationId, createdAt);

        IngestIdempotencyRecord record = mapper.toDomain(entity);

        assertThat(record.id()).isEqualTo(id);
        assertThat(record.agentId()).isEqualTo(agentId);
        assertThat(record.idempotencyKey()).isEqualTo("wamid-1");
        assertThat(record.conversationId()).isEqualTo(conversationId);
        assertThat(record.messageId()).isEqualTo(messageId);
        assertThat(record.invocationId()).isEqualTo(invocationId);
        assertThat(record.createdAt()).isEqualTo(createdAt);
    }
}
