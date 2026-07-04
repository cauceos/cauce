package dev.cauce.orchestration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The recorded outcome of one accepted message ingest, keyed by
 * {@code (agentId, idempotencyKey)}. A retried or redelivered request carrying the same key
 * is answered from this record instead of re-running the ingest, so at-least-once channel
 * deliveries and client retries never duplicate the USER message or its invocation.
 *
 * <p>Pure domain type: no persistence or framework dependencies. Immutable and always
 * complete: instances are only materialized from committed rows, and a committed row always
 * carries the full result — the transient lock state (result columns still NULL while the
 * ingest transaction holds the key) lives only inside that transaction and is never read
 * back as a domain object (see {@link InboundMessageService}).
 */
public record IngestIdempotencyRecord(UUID id,
                                      UUID agentId,
                                      String idempotencyKey,
                                      UUID conversationId,
                                      UUID messageId,
                                      UUID invocationId,
                                      Instant createdAt) {

    public IngestIdempotencyRecord {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(conversationId, "conversationId must not be null");
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(invocationId, "invocationId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
    }

    /** The stored ingest outcome, in the shape the ingest entry point returns. */
    public InboundMessageResult result() {
        return new InboundMessageResult(conversationId, messageId, invocationId);
    }
}
