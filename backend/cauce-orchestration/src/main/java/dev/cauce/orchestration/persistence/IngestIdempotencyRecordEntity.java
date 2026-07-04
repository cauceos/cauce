package dev.cauce.orchestration.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA persistence mapping for an ingest idempotency row. Infrastructure detail of
 * cauce-orchestration; the domain type is
 * {@link dev.cauce.orchestration.IngestIdempotencyRecord}, converted by
 * {@link IngestIdempotencyRecordMapper}.
 *
 * <p>The result columns are nullable at the schema level only for the transient lock state
 * the ingest transaction holds between inserting the key and recording the result (both
 * writes are native statements on {@link IngestIdempotencyRecordRepository}); a committed
 * row is always complete, which the {@code result_shape} CHECK (V15) enforces in depth.
 */
@Entity
@Table(name = "ingest_idempotency_records")
public class IngestIdempotencyRecordEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "agent_id", nullable = false, updatable = false)
    private UUID agentId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(name = "message_id")
    private UUID messageId;

    @Column(name = "invocation_id")
    private UUID invocationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IngestIdempotencyRecordEntity() {
        // for JPA
    }

    public IngestIdempotencyRecordEntity(UUID id, UUID agentId, String idempotencyKey,
                                         UUID conversationId, UUID messageId, UUID invocationId,
                                         Instant createdAt) {
        this.id = id;
        this.agentId = agentId;
        this.idempotencyKey = idempotencyKey;
        this.conversationId = conversationId;
        this.messageId = messageId;
        this.invocationId = invocationId;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public UUID getInvocationId() {
        return invocationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
