package dev.cauce.core.business.gap;

import dev.cauce.core.UuidGenerator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * A question an agent could not answer from the business's profile or catalog (ADR 0007, B4).
 * Personal business content: it lives in its table, where deletion operates, and never enters the
 * audit chain in clear.
 */
public record KnowledgeGap(UUID id, UUID tenantId, UUID agentId, UUID conversationId, String question,
                           Instant createdAt) {

    public static final int MAX_QUESTION_LENGTH = 1000;

    public KnowledgeGap {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(createdAt, "createdAt");
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        question = question.strip();
        if (question.length() > MAX_QUESTION_LENGTH) {
            throw new IllegalArgumentException("question must be at most " + MAX_QUESTION_LENGTH + " characters");
        }
    }

    /**
     * A question recorded at {@code recordedAt}, with a UUIDv7 id. The time comes from the caller's
     * clock, the database's in the application (ADR 0008): a digest selects its questions by it.
     */
    public static KnowledgeGap record(UUID tenantId, UUID agentId, UUID conversationId, String question,
                                      Instant recordedAt) {
        // Microseconds: what the database keeps, so a recorded gap equals the one read back.
        return new KnowledgeGap(UuidGenerator.newV7(), tenantId, agentId, conversationId, question,
                Objects.requireNonNull(recordedAt, "recordedAt").truncatedTo(ChronoUnit.MICROS));
    }
}
