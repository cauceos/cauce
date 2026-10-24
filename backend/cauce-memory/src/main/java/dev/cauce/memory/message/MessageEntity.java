package dev.cauce.memory.message;

import dev.cauce.core.message.MessagePart;
import dev.cauce.core.message.MessageParts;
import dev.cauce.core.message.MessageRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA persistence mapping for a message row. Infrastructure detail of cauce-memory;
 * the domain type is {@link dev.cauce.core.message.Message}, converted by
 * {@link MessageMapper}.
 */
@Entity
@Table(name = "messages")
public class MessageEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private MessageRole role;

    /**
     * The message's parts in their one JSON form ({@link MessageParts}), stored as jsonb (V54):
     * the row's only content, ADR 0004's single source of truth.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parts", nullable = false)
    private List<Map<String, Object>> parts;

    /** The internal failure type a FALLBACK message answers; null for every other role (V28). */
    @Column(name = "failure_type", length = 50)
    private String failureType;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * The message's position in its conversation, 1, 2, 3… assigned by the database on insert
     * (V44), never by a clock: what every read orders a conversation by.
     */
    @Column(name = "seq", insertable = false, updatable = false)
    private Long seq;

    protected MessageEntity() {
        // for JPA
    }

    /** Convenience constructor for a text message (USER / AGENT / SYSTEM): one text part. */
    public MessageEntity(UUID id, UUID conversationId, MessageRole role, String text, Instant createdAt) {
        this(id, conversationId, role, MessageParts.toJson(List.of(new MessagePart.Text(text))), null, createdAt);
    }

    public MessageEntity(UUID id, UUID conversationId, MessageRole role, List<Map<String, Object>> parts,
                         String failureType, Instant createdAt) {
        this.id = id;
        this.conversationId = conversationId;
        this.role = role;
        this.parts = parts;
        this.failureType = failureType;
        this.createdAt = createdAt;
    }

    public String getFailureType() {
        return failureType;
    }

    public UUID getId() {
        return id;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public MessageRole getRole() {
        return role;
    }

    public List<Map<String, Object>> getParts() {
        return parts;
    }

    /** The parts a voice note learned on transcription (ADR 0012, V2): the row's one rewrite. */
    public void replaceParts(List<Map<String, Object>> learned) {
        this.parts = learned;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Null on an entity not yet read back from the database (the trigger assigns it on insert). */
    public Long getSeq() {
        return seq;
    }
}
