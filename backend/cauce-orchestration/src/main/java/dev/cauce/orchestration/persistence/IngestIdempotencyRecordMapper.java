package dev.cauce.orchestration.persistence;

import dev.cauce.orchestration.IngestIdempotencyRecord;
import org.springframework.stereotype.Component;

/**
 * Hand-written mapping from the JPA {@link IngestIdempotencyRecordEntity} to the domain
 * {@link IngestIdempotencyRecord}. Deliberately one-way: rows are written exclusively by the
 * native insert-lock and record-result statements on {@link IngestIdempotencyRecordRepository},
 * so a {@code toEntity} counterpart would have no caller.
 *
 * <p>Only complete rows may be mapped: the domain record rejects null result ids. Reads go
 * through the repository finder, which only ever sees committed rows — and a committed row is
 * always complete (see V15).
 */
@Component
public final class IngestIdempotencyRecordMapper {

    public IngestIdempotencyRecord toDomain(IngestIdempotencyRecordEntity entity) {
        return new IngestIdempotencyRecord(
                entity.getId(),
                entity.getAgentId(),
                entity.getIdempotencyKey(),
                entity.getConversationId(),
                entity.getMessageId(),
                entity.getInvocationId(),
                entity.getCreatedAt());
    }
}
